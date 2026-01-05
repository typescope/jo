# Pattern Definitions

Patterns can be defined as reusable pattern functions using `pattern` definitions. This enables abstraction and reuse of common matching patterns.

## Basic Pattern Definitions

```jo
pattern Positive: Int =
  case x if x > 0

pattern Even: Int =
  case x if x % 2 == 0

// Use in match expressions
match n
case Positive & Even => "positive even"
case Positive => "positive odd"
case _ => "not positive"
end
```

## Syntax

```jo
pattern PatternName: Type =
  case pattern
```

Or with parameters:

```jo
pattern PatternName(param1: Type1, param2: Type2): Type =
  case pattern1
  case pattern2
  ...
```

## Pattern Parameters

Pattern definitions can have parameters that extract values from nested patterns. Parameter types must be explicitly specified:

```jo
pattern ValidUser(name: String, age: Int): Partial[User] =
  case u & match u.name with name & match u.age with age if age >= 18

// Use in match expressions
match user
case ValidUser(name, age) =>
  println("Welcome, " + name + ", age: " + age)
case _ =>
  println("Invalid user")
end
```

### Multiple Parameters

```jo
pattern Point2D(x: Int, y: Int): Point =
  case p then x = p.x, y = p.y

pattern Size[T](n: Int): List[T] =
  case Nil then n = 0
  case Cons(_, tail) then n = 1 + tail.size
  case Concat(lhs, rhs) then n = lhs.size + rhs.size

// Usage
match myList
case Size(n) if n > 10 => "Large list with " + n + " elements"
case Size(n) => "Small list with " + n + " elements"
end
```

## Multiple Cases

Pattern definitions can have multiple cases for different matching strategies:

```jo
pattern Size[T](n: Int): List[T] =
  case Nil then n = 0
  case Cons(_, tail) then n = 1 + tail.size
  case Append(prefix, _) then n = prefix.size + 1
  case Concat(lhs, rhs) then n = lhs.size + rhs.size
end
```

Each case must independently ensure all parameters are definitely bound.

## Parameter Binding Rule

**Rule:** Each pattern parameter must be definitely bound in each case of the pattern definition.

This ensures that using the pattern always provides all parameter values.

### Valid Examples

```jo
// ✓ OK - x is bound in all cases
pattern Value[T](x: T): Option[T] =
  case Some(x)
  case None then x = defaultValue()

// ✓ OK - name and age bound in both cases
pattern UserInfo(name: String, age: Int): User =
  case ValidUser(name, age)
  case PartialUser(n) then name = n, age = 0
```

### Invalid Examples

```jo
// ❌ Error: parameter x not bound in the pattern
pattern Invalid(x: Int): Option[Int] =
  case Some(y)  // x is not bound

// ❌ Error: parameter x only bound in Some, not in None
pattern Invalid(x: Int): Option[Int] =
  case Some(x) | None

// ❌ Error: parameter x only bound in first case
pattern Invalid(x: Int): Option[Int] =
  case Some(x)
  case None  // x not bound in this case
```

## Type Decomposition Patterns

Pattern definitions can decompose a type into a complete, disjoint partition by mapping values to a union type:

```jo
// Define the partition categories as a union type
union HttpStatus = Success | Redirect | ClientError | ServerError

// Map Int values to the union type
pattern HttpStatus(v: HttpStatus): Int =
  case x then v = begin
    if x >= 200 && x < 300 then Success
    else if x >= 300 && x < 400 then Redirect
    else if x >= 400 && x < 500 then ClientError
    else ServerError
  end

// Use in pattern matching - exhaustiveness is guaranteed
def classify(code: Int): String =
  match code
  case HttpStatus Success => "Success"
  case HttpStatus Redirect => "Redirect"
  case HttpStatus ClientError => "Client Error"
  case HttpStatus ServerError => "Server Error"
  end
```

### Integer Classification

```jo
union Signed = Neg | Zero | Pos

pattern Signed(v: Signed): Int =
  case x then v = begin
    if x > 0 then Pos
    else if x == 0 then Zero
    else Neg
  end

def sign(x: Int): String =
  match x
  case Signed Neg => "negative"
  case Signed Zero => "zero"
  case Signed Pos => "positive"
  end
```

This pattern works for any type where you can define a total mapping to a union type, enabling domain-specific partitions with compile-time verification.

## Generic Pattern Definitions

Pattern definitions can be generic:

```jo
pattern Head[T](x: T): List[T] =
  case [x, .._]

pattern NonEmpty[T]: List[T] =
  case [_, .._]

// Usage
match list
case Head(x) => "First element: " + x
case _ => "Empty list"
end

match list
case NonEmpty => "Has elements"
case _ => "Empty"
end
```

## Complex Pattern Definitions

### Statistics Pattern

```jo
pattern Stats[T](count: Int, sum: Int): List[Int] =
  case Nil then count = 0, sum = 0
  case Cons(x, tail) then
    count = 1 + tail.count,
    sum = x + tail.sum

match numbers
case Stats(count, sum) =>
  val avg = sum / count
  println("Count: " + count + ", Average: " + avg)
end
```

### Validation Pattern

```jo
pattern ValidEmail(email: String): String =
  case s if s.contains("@") && s.contains(".") then email = s

pattern ValidUser(name: String, email: String): User =
  case User(n, e) & ValidEmail(e) then name = n, email = e

match user
case ValidUser(name, email) =>
  println("Valid: " + name + " <" + email + ">")
case _ =>
  println("Invalid user")
end
```

## Benefits

### Abstraction

Encapsulate complex matching logic:

```jo
// Complex pattern extracted
pattern ValidConfig(host: String, port: Int): Config =
  case c & match c.host with Some(h) & match c.port with Some(p)
    if p > 0 && p < 65536
    then host = h, port = p

// Clean usage
match config
case ValidConfig(host, port) => connect(host, port)
case _ => useDefaults()
end
```

### Reusability

Define once, use many times:

```jo
pattern Positive: Int = case x if x > 0
pattern Even: Int = case x if x % 2 == 0

// Use in different contexts
match n
case Positive & Even => ...
end

for x in numbers if x is Positive do
  ...
end
```

### Exhaustiveness

Map arbitrary types to unions for exhaustive checking:

```jo
union Category = Low | Medium | High

pattern Category(c: Category): Int =
  case x then c = begin
    if x < 10 then Low
    else if x < 100 then Medium
    else High
  end

// Compiler ensures exhaustiveness
match value
case Category Low => ...
case Category Medium => ...
case Category High => ...
end  // All cases covered
```

## See Also

- [Pattern Forms](pattern-forms.md) - Basic pattern types
- [Pattern Composition](pattern-composition.md) - Combining patterns
- [Semantics](semantics.md) - Pattern matching rules
- [Definitions](../definitions/pattern-definitions.md) - Pattern definition syntax
