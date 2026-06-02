# Pattern Definitions

Pattern definitions create reusable, named patterns that can be used in `match`
expressions, `is` expressions, and other pattern contexts.

## Syntax

```
pattern_def = "pattern" [pre_params] ident [tparams] [params] ":" return_type "="
              pattern_case {pattern_case}
pattern_case = "case" pattern ["if" expr] ["then" assignments]
assignments  = ident "=" expr {"," ident "=" expr}
return_type  = type | "Partial" "[" type "]"
```

An irrefutable pattern uses a plain type as return type. A partial pattern (may fail to
match) uses `Partial[T]`.

## Basic Definitions

```jo
pattern Positive: Int =
  case x if x > 0

pattern Even: Int =
  case x if x % 2 == 0

pattern Empty[T]: List[T] =
  case []

match n
case Positive => "positive number"
case Even => "even number"
case _ => "other"
```

## Definitions with Parameters

Parameters let a pattern extract computed values from the matched value:

```jo
pattern Name(name: String): Student =
  case s then name = s.name

pattern Student(s: String, sex: Bool, age: Int): Student =
  case std then s = std.name, sex = std.sex, age = std.age

match student
case Name(n) => println("Name: " + n)
case Student(name, sex, age) =>
  println("Name: " + name + ", Sex: " + sex + ", Age: " + age)
end
```

## Multiple Cases

A pattern definition may have multiple cases, each of which must bind all parameters:

```jo
pattern Size[T](n: Int): List[T] =
  case Nil then n = 0
  case Cons(_, tail) then n = 1 + tail.size
  case Concat(lhs, rhs) then n = lhs.size + rhs.size

match myList
case Size(n) if n > 10 => "Large list"
case Size(n) => "Small list"
```

## Generic Definitions

Pattern definitions can be generic:

```jo
pattern Head[T](x: T): List[T] =
  case [x, .._]

pattern Tail[T](xs: List[T]): List[T] =
  case [_, ..xs]

pattern NonEmpty[T]: List[T] =
  case [_, .._]
```

## Operator Patterns

Patterns can use operator names with pre-parameters for infix usage:

```jo
pattern (head: T) :: [T](tail: List[T]): Partial[List[T]] =
  case [head, ..tail]

match list
case x :: rest => println(x)
case [] => println("empty")
```

## Irrefutable and Partial

An **irrefutable** pattern always matches its input type. A **partial** pattern may
fail, declared with `Partial[T]`:

```jo
// Irrefutable — always matches
pattern Name(name: String): Student =
  case s then name = s.name

// Partial — only matches positive numbers
pattern Positive: Partial[Int] =
  case x if x > 0

// Partial — only matches non-empty lists
pattern Head[T](x: T): Partial[List[T]] =
  case [x, .._]
```

## Parameter Binding Rule

Each parameter must be definitely bound in **every** case:

```jo
// ✓ OK - x bound in all cases
pattern Value[T](x: T): Option[T] =
  case Some(x)
  case None then x = defaultValue()

// ❌ Error - x not bound in the None case
pattern Invalid(x: Int): Option[Int] =
  case Some(x)
  case None
```

## See Also

- [Pattern Forms](../patterns/pattern-forms.md) - Pattern syntax and composition
- [Pattern Matching Semantics](../patterns/semantics.md) - Binding and exhaustiveness rules
- [Algebraic Data Types](union-definition.md) - Types designed for pattern matching
