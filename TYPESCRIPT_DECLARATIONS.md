# Design: Emitting TypeScript declaration files (`.d.ts`) for the exported API

Status: exploratory design / feasibility outline. No implementation yet.

## Context

Scala.js today emits JavaScript from the linker backend. This document explores emitting
**TypeScript**. The chosen scope is deliberately narrow and additive: **keep emitting `.js` exactly
as today, and additionally generate TypeScript declaration files (`.d.ts`) describing the
`@JSExport` / `@JSExportTopLevel` public API**, so that JS/TS consumers of a Scala.js build get type
information at their import sites.

Two findings reframe the problem:

1. **The default output is already "unminified."** `Printers.JSTreePrinter`
   (`linker/shared/src/main/scala/org/scalajs/linker/backend/javascript/Printers.scala`) is
   unconditionally indented (2-space, one statement per line, ES2015 `class`/`import`/`export`). The
   "minified" look comes from (a) the opt-in `minify` flag, which shortens only *property-namespace*
   names via `NameCompressor`, and (b) the deprecated, off-by-default Google Closure pass. So
   "unminified" is not the hard part — **types are**.

2. **There is no existing TypeScript / `.d.ts` machinery anywhere in the repo.** This is greenfield.

## The central problem: the export surface is `any`-typed in the IR

This is the crux that determines difficulty. The IR representation of an export is JS-interop-typed,
not Scala-typed:

- `TopLevelMethodExportDef` wraps a `JSMethodDef`; it is desugared with result type `AnyType`
  (`ClassEmitter.genTopLevelMethodExportDef`, ~line 1078) and its `ParamDef`s are effectively `any`.
- Per-class exports live in `LinkedClass.exportedMembers: List[JSMethodPropDef]`
  (`linker/shared/src/main/scala/org/scalajs/linker/standard/LinkedClass.scala:50`) — also
  `any`-typed forwarders.

The **real** Scala types survive elsewhere and are fully intact at link time:

- `LinkedClass.methods: List[MethodDef]` — each has an explicit `resultType: Type` and typed
  `ParamDef.ptpe` (`ir/shared/src/main/scala/org/scalajs/ir/Trees.scala`). Fields: `FieldDef.ftpe`.
- The IR `Type` lattice (`ir/shared/src/main/scala/org/scalajs/ir/Types.scala`) is rich and
  first-class about **nullability** and **exactness** (`ClassType(name, nullable, exact)`), which
  maps cleanly onto TS `T | null`.
- Precedent: `linker/shared/src/main/scala/org/scalajs/linker/backend/wasmemitter/TypeTransformer.scala`
  already turns IR `Type`s into a statically-typed target (Wasm). A `.d.ts` emitter is the analogous
  "IR `Type` → TS type" translation.

So the work is not "invent type info" — it is **reconnect the `any` export surface to the typed
members and render TypeScript**. Two strategies:

- **(A) Recover-from-IR, best-effort (recommended first).** In the linker, for each exported member,
  find the underlying typed `MethodDef`/`FieldDef` it forwards to (match by name/namespace), read
  its real signature, and translate. Falls back to `any` where the mapping is ambiguous. No compiler
  or IR-format changes. Has a fidelity ceiling (see below).
- **(B) Enrich-from-compiler, faithful (deferred).** Capture full Scala signatures in the compiler
  plugin (`compiler/.../nscplugin/GenJSExports.scala`, where they are still available pre-erasure)
  and thread them into the IR as metadata, then print them in the linker. Higher fidelity (could
  even recover generics/type parameters), but a large cross-cutting change: compiler + IR node +
  serializer/hashers + linker.

Recommendation: build (A) end-to-end first; treat (B) as a later fidelity upgrade if `any`-heavy
output proves inadequate.

## Where optimization happens, and why it matters here

The frontend pipeline is `LinkerFrontendImpl.link`
(`linker/shared/src/main/scala/org/scalajs/linker/frontend/LinkerFrontendImpl.scala:65`). Three
distinct things get lumped under "optimization":

1. **Reachability analysis + DCE (tree-shaking)** — `BaseLinker` + `analyzer/Analyzer`
   (`frontend/BaseLinker.scala:49`). Loads *all* `.sjsir`, computes reachability from roots
   (including `@JSExport`s), and assembles only reachable members. **Always runs; inherently
   whole-program.** It is not optional — Scala.js has no per-file / separate-compilation emit mode.
2. **The incremental optimizer** — `frontend/optimizer/IncOptimizer.scala` (+ `OptimizerCore.scala`;
   `ParIncOptimizer` on JVM), followed by the `Refiner`. Cross-method inlining, unboxing, constant
   folding, etc. **Optional**, gated in `frontend/LinkerFrontendImplPlatform.scala`
   (`createOptimizer` returns `None` when `config.optimizer` is false; default `true`). Disable via
   `scalaJSLinkerConfig ~= { _.withOptimizer(false) }`. Both `fastLinkJS` and `fullLinkJS` run it by
   default — `fullOpt` differs by adding `withSemantics(_.optimized).withMinify(true).withCheckIR(true)`
   (and historically Closure), not by toggling the optimizer.
3. **Google Closure Compiler** — backend whole-program JS minifier
   (`linker/jvm/.../backend/closure/ClosureLinkerBackend.scala`), JVM-only, deprecated, off by
   default.

Consequences for this design:

- Because whole-program DCE is mandatory and the final set of reachable exported types + the module
  layout are known only after linking, **`.d.ts` generation belongs in the linker**, not per-file in
  the compiler.
- The **optimizer inlines forwarders and renames locals/introduces synthetic names**, which is
  exactly what erodes Strategy A's ability to trace an export back to its typed member. A `.d.ts`
  spike (and possibly the feature) should run with `withOptimizer(false)` for the cleanest mapping.

## Recommended approach (Strategy A), phased

### Phase 1 — Plumbing (easy, low-risk)
- Add config knob `outputDeclarations: Boolean` (or similar) to
  `linker-interface/shared/src/main/scala/org/scalajs/linker/interface/StandardConfig.scala` (with
  `withX`, `Fingerprint`), and a `.d.ts` filename pattern to `interface/OutputPatterns.scala`
  (mirrors existing `jsFile = "%s.js"`).
- Thread through `standard/StandardLinkerBackend.scala` → `backend/LinkerBackendImpl.Config`.
- Expose via the sbt plugin `scalaJSLinkerConfig` and emit the extra file in the stage settings
  (`sbt-plugin/src/main/scala/org/scalajs/sbtplugin/ScalaJSPluginInternal.scala`; the `.d.ts` sits
  beside each `.js` module).

### Phase 2 — Collect the export surface (medium)
- Enumerate top-level exports per module from `ModuleSet` (`Module.topLevelExports:
  List[LinkedTopLevelExport]`) — the four `TopLevelExportDef` cases: JS class, module (singleton),
  method, field.
- Enumerate per-class exported members from `LinkedClass.exportedMembers`.
- For each exported member, resolve the underlying typed member (`LinkedClass.methods` / `fields`) to
  recover real signatures. Build a small "TS API model" (modules → declarations).

### Phase 3 — IR `Type` → TS type translator (medium; hard cases isolated here)
- A `TSTypeTransformer` analogous to `wasmemitter/TypeTransformer.scala`. Handle the mapping table
  below. Concentrate every fidelity compromise here so it is auditable.
- Requires reversing `NameGen` prefix-compression (`jl_`, `sci_`, …) back to readable names, and a
  policy for referencing types that are **not themselves exported** (fall back to `any`/`unknown`,
  or emit an opaque nominal alias).

### Phase 4 — `.d.ts` printer (easy–medium)
- A small dedicated text emitter (declarations only: `export class`, `export function`, member
  signatures, `readonly` fields, getters/setters → properties). Much smaller than the JS
  `Printers.scala` because there are no statement bodies. Emit one `.d.ts` per JS module, respecting
  `ModuleKind`.

### Phase 5 — Tests
- Follow `linker/shared/src/test/.../EmitterTest.scala` / `PrintersTest.scala` patterns; add
  golden-file `.d.ts` tests for representative exported APIs (class, object, top-level method/field,
  getter/setter, overloads, nullable returns).

## Difficulty matrix

**Easy**
- Config flag + output-pattern plumbing + sbt wiring (Phase 1).
- The `.d.ts` printer itself — signatures only, no bodies (Phase 4).
- Primitive mapping: `Int/Short/Byte/Float/Double` → `number`, `Boolean` → `boolean`, `StringType`
  → `string`, `VoidType`/`Unit` → `void`, `AnyType` → `any`.
- Enumerating the export surface (data is already collected in `ModuleSet` / `LinkedClass`).

**Medium**
- Nullability/exactness → `T | null` (info is present on `ClassType`/`ArrayType`).
- Reversing `NameGen` name mangling to readable, stable TS names.
- Overloads → TS overload signatures or unions; varargs → rest params; default params → optional
  params (`arg?`). Requires reading the underlying `MethodName` signatures.
- Getters/setters (`JSPropertyDef`) → TS property/accessor declarations.
- Matching each `any`-typed exported forwarder to its underlying typed `MethodDef`.

**Hard (fidelity ceiling of Strategy A)**
- **Generics are gone.** The IR is monomorphic/erased — no type parameters survive. Exported generic
  APIs (`List[String]`, `js.Promise[T]`, …) collapse to their erased form; TS output loses all type
  parameters unless Strategy B is adopted.
- **`Long` and `Char` have no native TS type.** `Long` is a runtime `RuntimeLong`, `Char` a boxed
  16-bit unit. Needs a policy (opaque/branded type, or `any`).
- **Scala collections/arrays exported to JS are opaque JS objects, not TS arrays** — cannot be typed
  as `T[]` faithfully.
- **Naming non-exported types.** A `.d.ts` can only name types that are themselves exported. Any
  exported member referencing a non-exported Scala class must fall back to `any`/`unknown` or an
  opaque alias — so the exported type graph should ideally be closed.
- **Exported classes with inheritance / mixins**, static vs instance members, module (`object`)
  singletons vs classes — each needs a faithful TS shape decision.

## IR `Type` → TS type mapping (starting table)

| IR `Type` | TS type | Notes |
|---|---|---|
| `BooleanType` | `boolean` | |
| `Int/Short/Byte/Float/Double` | `number` | |
| `LongType` | `?` | no native type — policy needed (opaque/branded/`any`) |
| `CharType` | `?` | boxed; policy needed |
| `StringType` | `string` | non-null variant |
| `VoidType` (Unit) | `void` | |
| `AnyType` | `any` | top of JS-passable values |
| `ClassType(n, nullable, _)` | `<TsName(n)>` (+ `\| null` if nullable) | only if `n` is exported/known, else `any` |
| `ArrayType` | `any` (or opaque) | Scala arrays are not JS arrays |
| `ClosureType(ps, r, _)` | `(…: …) => …` | function type |
| `NothingType` | `never` | |

## Key files

- Config/plumbing: `linker-interface/shared/.../interface/StandardConfig.scala`,
  `interface/OutputPatterns.scala`; `linker/shared/.../standard/StandardLinkerBackend.scala`,
  `backend/LinkerBackendImpl.scala`; `sbt-plugin/.../ScalaJSPluginInternal.scala`.
- Export surface (read-only inputs): `linker/shared/.../standard/LinkedClass.scala`
  (`exportedMembers`, `methods`, `fields`), `standard/LinkedTopLevelExport.scala`,
  `standard/ModuleSet.scala`; `ir/shared/.../ir/Trees.scala` (`TopLevelExportDef`, `JSMethodDef`,
  `JSPropertyDef`, `MethodDef`, `ParamDef`, `FieldDef`).
- Types: `ir/shared/.../ir/Types.scala`, `ir/.../Names.scala`; precedent
  `linker/shared/.../backend/wasmemitter/TypeTransformer.scala`.
- New code lives beside the JS backend: `linker/shared/.../backend/` (a new `declarations/`
  sub-package with a `TSDeclEmitter` + `TSDeclPrinter`), invoked from
  `backend/BasicLinkerBackend.emit` alongside the existing JS `OutputWriter`.
- Where the export bridge erases types (reference): `ClassEmitter.genTopLevelExports` /
  `genTopLevelMethodExportDef`; `compiler/.../nscplugin/GenJSExports.scala` (Strategy B source of
  truth).

## Open decisions
- **`Long`/`Char` policy**: `any`, or branded/opaque nominal types?
- **Non-exported referenced types**: `any`/`unknown`, or generated opaque nominal aliases?
- **Fidelity bar**: is best-effort Strategy A acceptable to start (generics lost, some `any`), or is
  faithful output (Strategy B, generics preserved) a hard requirement — which changes this from a
  linker-only feature into a compiler + IR-format change?

## Verification
- Add golden `.d.ts` tests under `linker/shared/src/test/...` (pattern: `EmitterTest.scala` /
  `PrintersTest.scala`).
- End-to-end: build a tiny sample with `@JSExportTopLevel` class + object + method + field,
  `fastLinkJS` with the new flag on (and `withOptimizer(false)` for the spike), then run
  `tsc --noEmit` against the emitted `.d.ts` importing the emitted `.js` to prove the declarations
  type-check.
- Confirm the existing JS output is byte-for-byte unchanged when the flag is off (feature is purely
  additive).
