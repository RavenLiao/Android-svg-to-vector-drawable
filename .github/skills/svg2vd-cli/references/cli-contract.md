# CLI Contract

Every normal invocation emits exactly one JSON document followed by a final newline on stdout. Stderr is reserved for diagnostics. Do not parse human-readable log lines as the result.

## Commands

- `convert --input <file-or-directory> --output <directory>`
- `convert --recursive` for an input tree
- `convert --overwrite` only with explicit user permission
- `convert --width-dp <n> --height-dp <n>` for requested dimensions
- `convert --add-aosp-header` to prepend the Android Open Source Project header
- `render --input <svg-or-vector-xml> --output <png> --size <n>`

## Exit Codes

| Code | Meaning |
| --- | --- |
| 0 | All requested work succeeded |
| 2 | Invalid command or arguments |
| 3 | One or more requested files failed |
| 4 | Java, JAR, or another required environment component is unavailable |
| 5 | Unexpected internal error |

Batch conversion is intentionally partial: successful files and their outputs are retained while failed files are reported in the same JSON result.

## Safety

The tool rejects symbolic-link inputs and unsafe output paths. Do not work around these diagnostics by resolving links manually. Existing outputs are not replaced unless `--overwrite` is supplied.

