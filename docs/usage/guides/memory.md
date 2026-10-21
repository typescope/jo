# Memory in Native Programs

Experimental. This guide covers the LLVM backend (`jo compile --llvm`).

Jo programs compiled to native code allocate freely and never stop to collect
garbage. For most programs, such as command-line tools, batch jobs and tests,
memory is simply returned when the program exits. Allocation stays fast, and
runs never pause.

When a long-running program needs bounded memory, name the functions whose
work is done when they return:

```sh
jo compile --llvm --region app.handleRequest src/App.jo -o app
```

Each call of `handleRequest` then runs in a memory region of its own. When the
call returns, what it allocated is freed, except for what it returned or stored
elsewhere. The next call reuses the memory.

Regions are an optimization, like `-O2`. They never change what a program
computes, only its peak memory. You add them where measurements show they help,
without annotations in the code.

## An example

A server handles one request after another. Each request builds temporary
strings and lists, and only the response leaves the handler:

```jo
namespace app

def handleRequest(request: String): Int =
  val words = request.split(" ")
  // ... build temporary data from the words ...
  words.size

def main =
  var total = 0
  var i = 0
  while i < 100000 do
    total = total + handleRequest("a request with a few words")
    i = i + 1
  end
  println total
```

Compiled without regions, its memory grows with every request until the
program exits. With `--region app.handleRequest`, the temporaries of each
request are freed when it returns, and peak memory stays at the size of one
request.

On a Linux test machine, the example peaks at 756 MB without the region and at
3 MB with it.

## What is kept

A region frees only what can no longer be reached. An object survives the call
when it is

- the result of the function, or reachable from it,
- stored into an object that existed before the call, or into an array or a
  closure that is reached from one, or
- reachable from such an object.

Survivors move to the caller's memory and are freed when the caller's region,
if any, finishes with them. You never decide what is kept, so a region cannot
free something the program still uses.

## Choosing region functions

A region pays off when most of what a function allocates is garbage by the time
it returns.

- **Good:** a function called again and again, for example from a loop, that
  builds temporary data and returns a small result such as a number, a flag or
  `Unit`. A request handler, a simulation step and the body of a processing
  loop are typical.
- **Bad:** a function that returns most of what it builds, such as a parser or
  a list builder. It frees nothing, and its results are checked again at every
  enclosing region. A recursive builder that is a region can become much
  slower.
- **Neutral:** a function that allocates little. It costs a few instructions
  per call and frees little.

Measure before and after. On Linux, `/usr/bin/time -v ./app` reports the
maximum resident set size and the running time.

## Notes

- Methods are named `namespace.Class.method`, for example
  `--region app.Parser.parse`.
- The compiler warns about a name that matches no compiled function.
- Functions of the runtime cannot be region functions.
- Garbage created inside one long-running loop is freed only when the function
  that runs the loop returns. Move the loop body into a function and make that
  function a region.
