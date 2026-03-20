# Versioning

Jo uses semantic versioning (`MAJOR.MINOR.PATCH`) with Cargo-style range syntax:

| Spec          | Meaning           |
|---------------|-------------------|
| `"^1.2.0"`    | `>=1.2.0, <2.0.0` |
| `"~1.2.0"`    | `>=1.2.0, <1.3.0` |
| `">=1.0, <2"` | explicit range    |
| `"1.2.0"`     | exact version     |
