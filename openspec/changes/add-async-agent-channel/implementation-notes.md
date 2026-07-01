## Feasibility Gate Result

Date: 2026-07-01

The initial Chaquopy/Pydantic AI feasibility gate is blocked with stock Chaquopy package resolution.

Verified:

- Chaquopy 17.0.0 can be applied to the Android app module with Python 3.13 and `arm64-v8a`.
- Chaquopy Gradle tasks are registered, including `installDebugPythonRequirements`.
- A minimal `agent_bridge.py` source file can be packaged as app Python source.
- A Kotlin smoke wrapper can call the `agent_bridge.run_agent` function through the Chaquopy API.

Failed dependency sets:

- `pydantic-ai-slim[openai]==1.29.0`
  - Fails because OpenAI depends on `jiter`, and Chaquopy reports no matching Android distribution for `jiter`.
- `pydantic-ai-slim==1.29.0`
  - Fails because Pydantic depends on `pydantic-core`, and Chaquopy reports no matching Android distribution for `pydantic-core`.

Representative Gradle command:

```sh
nix develop --no-write-lock-file -c gradle :app:installDebugPythonRequirements
```

Representative failure:

```text
Additionally, some packages in these conflicts have no matching distributions available for your environment:
    pydantic-core
```

Updated direction:

The selected LLM provider is OpenRouter through Pydantic AI's OpenAI-compatible provider path. This intentionally keeps the OpenAI SDK dependency and therefore accepts the `jiter` native-wheel requirement.

Conclusion:

The embedded Pydantic AI approach cannot proceed with stock Chaquopy package resolution because `pydantic-core` is unavailable for the target environment. The OpenRouter/OpenAI-compatible path additionally requires a Chaquopy-compatible `jiter` wheel. Full Agent channel implementation is paused before production dispatch wiring until both wheels are supplied or built.

Potential paths before continuing:

1. Provide or build Chaquopy-compatible Android wheels for `pydantic-core` and `jiter`.
2. Configure `pydantic-ai-slim[openai]` with Pydantic AI's OpenAI-compatible provider and OpenRouter base URL.
3. Re-run `:app:installDebugPythonRequirements` and the bridge import smoke call before continuing channel implementation.
