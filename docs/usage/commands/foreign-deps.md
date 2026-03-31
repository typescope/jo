# Foreign Dependencies

Foreign package dependencies (Python or Ruby packages required by FFI-based libraries) are not installed automatically. The runtime (`python` or `ruby`) must be installed and available on `PATH`, and its packages must be set up manually.

## Python

If a `.venv/` directory exists in the project root, `jo run` and `jo test` use `.venv/bin/python` automatically; otherwise they fall back to the system `python`.

Create a virtual environment and install the required packages:

```sh
python -m venv .venv
.venv/bin/pip install <package1> <package2> ...
```

## Ruby

If a `Gemfile` exists in the project root, `jo run` and `jo test` use `bundle exec ruby` automatically so that gems managed by Bundler are visible; otherwise they fall back to the system `ruby`.

Add required gems to a `Gemfile` and install them:

```sh
bundle init
bundle add <gem1> <gem2> ...
bundle install
```
