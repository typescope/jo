# Foreign Dependencies

Show how to inspect and install foreign package dependencies for FFI-based projects.

## Usage

```
jo deps --pip
jo deps --gems
```

## Options

| Option          | Description                                           |
|-----------------|-------------------------------------------------------|
| `--spec <file>` | Build spec to use. Default: `jo.toml`.                |
| `--pip`         | Show merged Python foreign deps instead of Jo deps.   |
| `--gems`        | Show merged Ruby foreign deps instead of Jo deps.     |

## Examples

```sh
jo deps              # resolved Jo dependency tree
jo deps --pip        # merged pip.txt content (all transitive Python deps)
jo deps --gems       # merged gems.txt content
```

## Installing Foreign Package Dependencies

Foreign package dependencies are not installed automatically. The runtime (`python` or `ruby`) must be installed and available on `PATH`.

**Python:**

If a `.venv/` directory exists in the project root, `jo run` and `jo test` use `.venv/bin/python` automatically; otherwise they fall back to the system `python`.

```sh
python -m venv .venv
jo deps --pip > requirements.txt
.venv/bin/pip install -r requirements.txt
```

**Ruby:**

If a `Gemfile` exists in the project root, `jo run` and `jo test` use `bundle exec ruby` automatically so that gems managed by Bundler are visible; otherwise they fall back to the system `ruby`.

```sh
jo deps --gems > Gemfile
bundle install
```
