# jo run

Run the application — first build if needed.

## Usage

```
jo run [--spec <file.toml>]
```

## Options

| Option          | Description                            |
|-----------------|----------------------------------------|
| `--spec <file>` | Build spec to use. Default: `jo.toml`. |

## What It Does

`jo run` rebuilds the app if sources or dependencies have changed, then runs the generated script using the target runtime. The runtime (`python` or `ruby`) must be installed and available on `PATH`.

- **Python**: if a `.venv/` directory exists in the project root, `jo run` uses `.venv/bin/python` automatically; otherwise falls back to the system `python`.
- **Ruby**: `jo run` uses `bundle exec ruby` automatically so that gems managed by Bundler are visible. Bundler looks for a `Gemfile` in the current directory.

## Foreign Package Dependencies

Foreign package dependencies are not installed automatically. Install them beforehand if needed.

**Python:**

```sh
python -m venv .venv
jo deps --pip > requirements.txt
.venv/bin/pip install -r requirements.txt
```

**Ruby:**

```sh
jo deps --gems > Gemfile
bundle install
```

## Notes

Only valid for app builds. Running on a library spec is an error.

## Examples

```sh
jo run
jo run --spec my-agent.toml
```
