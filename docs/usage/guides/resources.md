# Resources

Resources are data files that travel with a Jo module: HTML, JavaScript, images,
schemas, templates, fixtures, and other non-`.jo` files.

## Declaring Resources

Add `resources` to the module that owns the files:

```toml
jo = "1.0"
default = "app"

[module.web]
kind = "lib"
src = ["src/"]
resources = [
  "assets/",
  "client/:public/",
  "templates/index.html:views/index.html",
]

[module.web.package]
name = "my-web"
version = "1.0.0"

[module.app]
kind = "app"
platform = "python"
src = ["app/"]
modules = ["web"]
```

Each resource entry is a mapping. The short form `source` means `source:source`;
use `source:destination` to give files a different runtime resource path.

A directory source includes regular files recursively and preserves paths
relative to that source directory under the destination. A file source maps to
exactly the destination file path.

```text
assets/app.js              -> assets/app.js
assets/logo.svg            -> assets/logo.svg
client/app.js              -> public/app.js
client/img/logo.svg        -> public/img/logo.svg
templates/index.html       -> views/index.html
```

The list is a set of includes. If two entries include the same destination, Jo
reports a duplicate resource target instead of letting a later entry override an
earlier one.

## Path Rules

Resource paths are logical package paths. Use `/` on every operating system:

```toml
resources = ["assets/logo.svg"]       # ok
resources = ["client/:public/"]       # ok
resources = ['assets\logo.svg']       # error
```

Invalid entries include:

```toml
resources = [":assets"]               # error: empty source
resources = ["assets:"]               # error: empty destination
resources = ["a:b:c"]                 # error: too many ':' separators
resources = ["../secret.txt"]         # error: parent segment
resources = ["assets:../secret.txt"]  # error: destination parent segment
resources = ["/tmp/secret.txt"]       # error: absolute path
resources = ["C:/secret.txt"]         # error: Windows absolute path
resources = ["./assets"]              # error: current-directory segment
resources = ["assets//logo.svg"]      # error: empty segment
```

Source and destination syntax is validated when Jo reads `jo.toml`. Missing
files, symlinks, and duplicate expanded destinations are checked by commands
that collect resources, such as `jo package` and app builds.

The destination path never comes from normalizing an unsafe input. Write the
canonical project-relative path directly. A trailing `/` is allowed and has no
effect on the logical destination path.

## Packaging

`jo package <module>` copies that module's own resources into the `.joy` file
under `resources/`:

```text
my-web-v1.0.0.joy
  meta.toml
  my/
    web/
      Web.sast
  resources/
    assets/
      app.js
      logo.svg
    public/
      app.js
      img/
        logo.svg
    views/
      index.html
```

The companion source archive includes the original source-side resource files,
not the mapped destination names. This makes package releases reproducible and
auditable.

## App Builds

When Jo builds an app, it copies resources from the app and its selected
dependency closure beside the generated program:

```text
.build/app/jo-1.0/target/app.py
.build/app/jo-1.0/target/resources/<owner>/<resource-path>
```

The `<owner>` directory is described in [Owner Names](#owner-names).

This is an app-global resource directory. Any code running in the app that has
the `resources` capability can read any copied owner path if it knows the owner
and resource path. Resource owners prevent file-name collisions; they are not a
least-privilege access control boundary.

Plain lib builds do not copy resources. Only app builds do.

## Owner Names

Resource reads name an owner explicitly:

```jo
resources.readText("my-web", "views/index.html")
```

The owner name is derived from the module or package that declared the
resources:

- registry package dependency: package name
- source module with `[module.<id>.package]`: package name
- source module without package metadata: module id

If two modules or packages in one app resource closure derive the same owner and
both declare resources, the app build fails. This matters most for external
unpublished source modules because module ids are scoped to their own project.
Use package metadata when an external source module needs a stable owner that
will not collide with another module id.

## Reading Resources

Resource access uses the `resources` capability through `jo.resource.Resources`:

```jo
import jo.resource.*

def loadTemplate(): Result[String, String] receives resources =
  resources.readText("my-web", "views/index.html")
```

The runtime does not bind this context parameter by default. Code that has FFI
access can opt in explicitly.

Python:

```jo
import jo.resource.*
import jo.py.resource.*

def main: Unit receives IO.stdout =
  with resources = new py.resource.ResourceBundle() in
    run()
```

Ruby:

```jo
import jo.resource.*
import jo.rb.resource.*

def main: Unit receives IO.stdout =
  with resources = new rb.resource.ResourceBundle() in
    run()
```

Enable the matching FFI API on the module that creates the bundle:

```toml
[module.app]
kind = "app"
platform = "python"
enable-ffi = true
src = ["app/"]
resources = ["assets/"]
```

`readText` uses UTF-8. Use `readBytes` for binary files such as images.

## Runtime Safety

`ResourceBundle` validates owner and resource paths before reading:

- owners cannot be empty, `.`, `..`, or contain `/` or `\`
- resource paths must be relative
- resource paths use `/` only
- `.` and `..` segments are rejected
- symlinks escaping the owner root are rejected

The bundle resolves resources relative to the generated program path, not the
process working directory. This lets apps start from another directory while
still finding resources copied beside the app output.

Do not put secrets in resources. They are bundled application assets copied with
the app, and access is app-global once code receives the `resources` capability.
Use a separate capability, configuration source, or secret store for data that
must be private to one dependency or unavailable to other app code.
