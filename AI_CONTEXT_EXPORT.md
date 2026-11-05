# AI Context Export

`export_ai_context.ps1` turns this repository into a **self-contained, shareable context
bundle** for an external AI that has no access to the filesystem and no network
connectivity. It is a read-only tool: it never touches application code, dependencies or
build configuration.

## Running it

```powershell
# From the repository root, with PowerShell 5.1 or PowerShell 7+
.\export_ai_context.ps1
```

The export is written to `ai-context-export/` in the repository root. That directory is
**generated output** and is already listed in `.gitignore`, so it will not show up in
`git status`.

### Options

| Option | Default | Purpose |
| --- | --- | --- |
| `-OutputDirectory <path>` | `ai-context-export` | Where to write the export. Relative paths are resolved against the repository root. |
| `-MaxChunkSizeMB <n>` | `4` | Soft size limit for one exported text file. Larger sections are split into numbered parts. |
| `-ExcludeTests` | off | Skip unit-test and instrumented-test sources. |
| `-ExcludeVendoredSources` | off | Skip third-party vendored sources (LibreOfficeKit bindings, AndroidX print helper). |
| `-DryRun` | off | Report what would be exported; write nothing. |

Examples:

```powershell
.\export_ai_context.ps1 -DryRun
.\export_ai_context.ps1 -MaxChunkSizeMB 8 -OutputDirectory C:\Temp\zenlabs-context
.\export_ai_context.ps1 -ExcludeTests -ExcludeVendoredSources
```

## What gets generated

| File | Contents |
| --- | --- |
| `PROJECT_OVERVIEW.txt` | Application identity, Gradle modules, build configuration, ABIs, native libraries, manifest permissions and components, package layout. |
| `PROJECT_STRUCTURE.txt` | The relevant directory tree, plus the directories that were pruned. |
| `SOURCE_CODE.txt` | Every source file with its **exact repository path** and **complete contents**, each wrapped in a `FILE:` / `END OF FILE:` boundary. |
| `RESOURCES_AND_ASSETS.txt` | Resource, asset, native-library and prebuilt-binary inventory with formats, sizes and image dimensions. |
| `DEPENDENCIES.txt` | The resolved version catalogue (coordinate → version → modules that use it), plugins, per-module dependencies and the raw Gradle configuration. |
| `DOCUMENTATION.txt` | `README.md`, `docs/`, licence and store-listing text. |
| `GIT_STATUS.txt` | Branch, latest commit, remotes, working-tree state and recent history. |
| `MANIFEST.txt` | Everything that was exported, everything that was excluded **and why**, and the redaction log. |
| `README.txt` | Instructions for the AI that receives the bundle. |
| `EXPORT_SUMMARY.txt` | Counts and sizes. |

If a section exceeds `-MaxChunkSizeMB` it is split into numbered parts
(`SOURCE_CODE.part001.txt`, `SOURCE_CODE.part002.txt`, …). The canonical file name
becomes an index that lists the parts. **No file is ever truncated** — a single file
larger than the limit gets a part of its own and is written out in full.

## Sharing it with an external AI

1. Run the script and confirm the summary at the end (files exported / excluded /
   redactions / total size).
2. Check `MANIFEST.txt` section 3 to see what was left out and why, and section 5 for
   anything that was redacted.
3. Zip the whole `ai-context-export/` directory and send it as one attachment:

   ```powershell
   Compress-Archive -Path .\ai-context-export\* -DestinationPath .\ai-context-export.zip
   ```

4. Tell the AI to read `README.txt` inside the bundle first. It lists the recommended
   reading order and explains the file-boundary format, so the AI can cite exact file
   paths when proposing changes.

Everything is plain UTF-8 text with LF line endings, so the bundle can be pasted
directly into a chat, uploaded as a file, or fed to a tool that reads text.

## Secrets

The exporter is designed so that credentials do not leave the machine:

* `local.properties`, `*.jks`, `*.keystore`, `*.p12`, `*.pem`, `.env` and similar files
  are **excluded outright** and listed in `MANIFEST.txt`.
* Credential-shaped literals inside exported text are **redacted** to
  `<<REDACTED:...>>` markers and logged in `MANIFEST.txt` section 5. The surrounding
  code is left intact.
* Binary files (`.so`, `.onnx`, `.zip`, `.xz`, images, fonts, jars) are not exported at
  all — only inventoried in `RESOURCES_AND_ASSETS.txt`.

Detection uses high-confidence provider token shapes (AWS, Google, GitHub, Slack, JWT,
npm, …) plus an entropy check on credential-named assignments. Code that merely
*mentions* a credential — for example `password = parameters?.password` — is correctly
left untouched.
