# Jo Syntax Reference

Jo's syntax is designed to be clean, expressive, and accessible to both humans and LLMs. This reference covers the language's lexical structure, grammatical forms, and organizational principles.

## Lexical Elements

### Identifiers and Operators

```
letter   = "A" | "B" | ... | "Z" | "a" | "b" | ... | "z"
digit    = "0" | "1" | "2" | "3" | ... | "7" | "8" | "9"
opchar   = "+" | "-" | "*" | "/" | "%" | "|" | "&" | "^" |
           ">" | "<" | "=" | "!" | "?"

name     = (letter | "_") {letter | digit | "_"}
operator = opchar {opchar}
ident    = name | operator
```

### Literals

```
integer = ["-"] digit {digit}
boolean = "true" | "false"
char    = single character in single quotes
string  = text in double quotes
```

### Comments

Jo supports both line and block comments:

```jo
// Line comment

//[ Block comment //]

///[
  Nested block comment
  ///[ with inner blocks ///]
///]
```

Block comments require matching numbers of slashes in opening and closing delimiters.

## Keywords

Core language keywords:

```
val var fun def type data param class new alias as
if then else while do begin end match case with allow
import namespace section receives pattern auto defer
```

## Program Structure

### Namespaces

Each source file defines a namespace:

```jo
namespace io.net

import system
import data.List

type Connection = { host: String, port: Int }

def connect(conn: Connection): Unit = ...
```

Rules for namespaces:

- If no namespace is specified, the filename becomes the default namespace
- Namespaces form a tree structure with branch and leaf nodes
- Branch namespaces contain only other namespaces
- Leaf namespaces contain functions and type definitions
- All dependencies must be explicitly imported

### Imports

Import statements make other namespaces available:

```jo
import lib.*            // Import entire namespace
import system.File.read // Import specific function
```

Exceptions to import requirements:

- Current namespace is implicitly imported
- Predefined language constructs are always available

## Expressions and Statements

### Basic Expressions

Jo uses a word-based syntax where expressions are sequences of words:

```jo
// Function application
println "Hello, world!"
add 1 2

// Method calls
list.append(item)
obj.method(arg1, arg2)

// Operators
1 + 2 * 3
x == y && z != w
```

### Control Flow

#### Conditionals

```jo
if condition then
  action1()
else
  action2()
end

// Inline form
val result = if x > 0 then "positive" else "negative"
```

#### Loops

```jo
while condition do
  action()
  updateCondition()
end
```

#### Pattern Matching

```jo
match value
case #Some(x) =>
  println ("Found: " + x)
case #None =>
  println "Nothing found"
end
```

#### Assignment

```jo
// Variable assignment
var counter = 0
counter = counter + 1

// Array element assignment
var arr = Array.create(10, 0)
arr[0] = 42

// Object field assignment
var config = { timeout = 30, retries = 3 }
config.timeout = 60
```


## Type System

### Basic Types

```jo
type Person = { name: String, age: Int }
type Option[T] = #Some(value: T) | #None
type Result[T, E] = #Ok(value: T) | #Error(error: E)
```

### Function Types

```jo
type Handler = (String, Int) => Unit
type Processor = String => String receives IO
type Callback = () => Unit receives logger
```

### Object Types

```jo
type Logger = {
  def info(message: String): Unit
  def error(message: String): Unit
  var level: String
}
```

## Definitions

### Function Definitions

```jo
// Simple function
def greet(name: String): String = "Hello, " + name

// Function with effects
def writeFile(path: String, content: String): Unit receives IO.write =
  File.write(path, content)

// Function with context parameters
def process(data: List[Int]): Unit receives logger =
  logger.info("Processing started")
  // ... processing logic
```

### Value Definitions

```jo
val immutable = 42
var mutable = "can change"

// Type annotations
val typed: String = "explicitly typed"
var counter: Int = 0
```

### Pattern Definitions

```jo
pattern Name(name: String): Student =
  case #Student name _ _

pattern Some[T](value: T): #Some(v: T) =
  case #Some value

pattern None: #None = case #None

pattern Student(s: String, sex: Bool, age: Int): Student =
  case #Student s sex age
```

### Type Definitions

```jo
// Basic type alias
type UserId = Int
type Name = String

// Record types
type Config = {
  host: String,
  port: Int,
  timeout: Int
}

// Algebraic data types
data List[T] = #Nil | #Cons(head: T, tail: List[T])

data Tree[T] =
  | #Leaf(value: T)
  | #Branch(left: Tree[T], right: Tree[T])
```

### Context Parameter Definitions

```jo
// Simple parameter
param logger: Logger

// Parameter with default
param timeout: Int = 30

// Multiple parameters
param database: Database
param cache: Cache
```

### Section Definitions

```jo
section Database
  def connect(url: String): Connection = ...
  def query(sql: String): ResultSet = ...
  def close(conn: Connection): Unit = ...
end

section Validation
  def validateEmail(email: String): Bool = ...
  def validateAge(age: Int): Bool = ...
end
```

### Deferred Function Definitions

```jo
// Deferred function declaration
defer def authenticate(token: String): User

// Usage in other functions
def handleRequest(request: Request): Response =
  val user = authenticate(request.token)
  // ... handle request

// Linked at compile time with -link option
// bin/jo build -link MyApp.authenticate=OAuth.verify app.jo -o app
```

### Alias Definitions

```jo
// Function aliases
alias def + = jo.Int.+
alias def && = jo.Bool.&&

// Pattern aliases
alias pattern Some = jo.Option.Some
alias pattern None = jo.Option.None

// Parameter aliases
alias param logger = system.Logger

// Auto aliases for automatic resolution
auto alias def intEq = jo.Eq.Defaults.intEq
```


## Syntax Conventions

### Indentation

Jo is indentation-sensitive. Consistent indentation indicates block structure:

```jo
if condition then
  statement1()
  statement2()
  if nested then
    nestedStatement()
  end
end
```

### Naming Conventions

- Types: PascalCase (`List`, `UserAccount`)
- Functions: camelCase (`processData`, `validateInput`)
- Variables: camelCase (`userName`, `totalCount`)
- Constants: camelCase (`maxRetries`, `defaultTimeout`)
- Namespaces: dot.separated (`data.collections`, `io.network`)

### Expression Forms

Jo supports multiple expression styles:

```jo
// Prefix notation
add(1, 2)
println("message")

// Infix notation (for operators)
1 + 2
x == y

// Postfix notation (for parameterless methods)
list.length
file.exists

// Mixed forms
users.filter(u => u.active).map(u => u.name)
```

This syntax reference provides the foundation for understanding Jo's grammatical structure. The language prioritizes clarity and expressiveness while maintaining formal precision for both human developers and LLM code generation.

## Formal Grammar

### Lexical Grammar

```
letter = "A" | "B" | ... | "Z" | "a" | "b" | ... | "z"
digit  = "0" | "1" | "2" | "3" | ... | "7" | "8" | "9"
opchar = "+" | "-" | "*" | "/" | "%" | "|" | "&" | "^" |
         ">" | "<" | "=" | "!" | "?"

name     = (letter | "_") {letter | digit | "_"}
operator = opchar {opchar}
ident    = name | operator
integer  = ["-"] digit {digit}
boolean  = "true" | "false"
char     = single character in single quotes
string   = text in double quotes

comment = line_comment | block_comment
line_comment = "//" {any character} newline
block_comment = "/" "/" {"/"} "[" {any character} "/" "/" {"/"} "]"

Note: In block_comment, the closing delimiter must have the exact same
number of slashes as the opening delimiter (minimum 2).
Example: //[ ... //], ///[ ... ///], ////[ ... ////]
```

### Abstract Syntax

```
namespace = ["namespace" qualid] {import} {toplevel_def} EOF

section = "section" ident {toplevel_def} ["end"]

toplevel_def = typedef | fundef | paramdef | aliasdef | patdef | datadef | 
               classdef | section

qualid = ident | qualid "." ident

import = "import" qualid

expr = word {word}

word = integer | boolean | char | string | ident | fence | record |
       apply | select | tag | lambda | object | list | new_expr |
       begin_block | type_apply | bracket_apply

phrase = simple_phrase | assign | valdef | fundef | patdef | typedef | 
         while | if | match

block = {phrase}

begin_block = "begin" block ["end"]

select = word "." ident

apply = word args
args = "(" [expr {"," expr}] ")"

bracket_apply = word "[" expr {"," expr} "]"
type_apply = word targs

new_expr = "new" qualid [targs] [args]

simple_phrase = expr | type_ascribe | with_clause | allow_clause

with_clause = simple_phrase "with" with_bindings
with_bindings = with_binding {"," with_binding}
with_binding = qualid "=" expr

allow_clause = simple_phrase "allow" qualid {"," qualid}

fence = "(" phrase ")"
assign = (ident | select | bracket_apply) "=" block
if = "if" expr "then" block ["else" block] ["end"]
while = "while" expr "do" block ["end"]

list = "[" [expr {"," expr}] "]"

record = "{" [named_args] "}"
named_args = named_arg {"," named_arg}
named_arg = ident ":" expr

tag = "#" ident

type_ascribe = simple_phrase "as" simple_type

object = "{" [members] "}"
members = member {[","] member}
member = valdef | defdef

lambda = (param_section | ident) "=>" block

match = "match" expr {case} ["end"]
case = "case" pattern "=>" block

pattern = expr_pattern ["if" expr] ["then" pattern_bindings]

pattern_bindings = pattern_binding {"," pattern_binding}
pattern_binding = ident "=" expr

expr_pattern = simple_pattern {simple_pattern}

simple_pattern = literal_pattern | qualid | tag | type_pattern | 
                 ascribe_pattern | apply_pattern | "(" pattern ")" | 
                 sequence_pattern

literal_pattern = integer | boolean | char | string
type_pattern = ident ":" type
ascribe_pattern = ident "@" simple_pattern
apply_pattern = (tag | qualid) "(" [pattern {"," pattern}] ")"

sequence_pattern = "[" [expr_pattern {"," expr_pattern}] "]"

modifier = "auto" | "defer"

valdef = {modifier} ("val" | "var") ident [":" type] "=" block

fundef = {modifier} "def" [param_section] ident [tparams] [param_section] 
         [auto_section] [":" type] [receive_params] ["=" block] ["end"]

classdef = {modifier} "class" ident [tparams] {defdef | val_decl} ["end"]

defdef = "def" ident [tparams] [param_section] [":" type] [receive_params] 
         "=" block ["end"]

patdef = "pattern" ident [tparams] [param_section] [":" type] "=" cases ["end"]
cases = case {"case" pattern}

datadef = "data" ident [tparams] ([param_section] | "=" branch {"|" branch})
branch = ident [param_section]

paramdef = "param" param ["=" block]

aliasdef = {modifier} "alias" ("def" | "pattern" | "param") ident "=" qualid

typedef = "type" [tparams] ident [tparams] ["=" type | "<:" type]
tparams = "[" tparam {"," tparam} "]"
tparam = ident

applied_type = ident targs
targs = "[" type {"," type} "]"

type = union_type | expr_type | fun_type

union_type = simple_type {"|" simple_type}

expr_type = simple_type {simple_type}

simple_type = qualid | record_type | tag_type | applied_type | fun_type | 
              object_type | "(" type ")"

record_type = "{" [fields] "}"
fields = field {[","] field}
field = ident ":" type

tag_type = "#" ident ["(" tag_param {"," tag_param} ")"]
tag_param = [ident ":"] type

fun_type = param_types "=>" type [receive_params]
param_types = simple_type | "()" | "(" type {"," type} ")"

receive_params = "receives" qualid {"," qualid}

object_type = "{" [member_decls] "}"
member_decls = member_decl {[","] member_decl}
member_decl = method_decl | val_decl
method_decl = "def" ident param_section ":" type [receive_params]
val_decl = ("val" | "var") ident ":" type

param_section = "(" [params] ")"
params = param {"," param}
param = ident ":" type

auto_section = "(" "auto" params ")"
```
