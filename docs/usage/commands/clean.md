# jo clean

Remove build artifacts for the current project.

## Usage

```
jo clean [module]
```

## What It Does

With no module argument, `jo clean` deletes the project's whole `.build/` directory. With a module argument, it deletes `.build/<module>/` only and leaves the other modules' output in place.

Either way this removes compiled `.sast` files, target output, release and doc output, and the `.done` sentinels used for incremental builds.

**Only the current project is cleaned.** Projects reached through `path` dependencies have their own `.build/` directories and must be cleaned separately by running `jo clean` in each one. The global package cache (`~/.jo/cache/packages/`) is never affected.

## Examples

```sh
jo clean      # remove .build/
jo clean api  # remove .build/api/ only
```
