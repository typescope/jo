# Pattern Definitions

Pattern definitions create reusable pattern predicates for matching and extracting data.

## Basic Pattern Definitions

```jo
pattern Positive: Int =
  case x if x > 0

pattern Even: Int =
  case x if x % 2 == 0

pattern Empty[T]: List[T] =
  case []

// Use in match expressions
match n
case Positive => "positive number"
case Even => "even number"
case _ => "other"
end
```

## Pattern Definitions with Parameters

Extract values using parameters:

```jo
pattern Name(name: String): Student =
  case s then name = s.name

pattern Student(s: String, sex: Bool, age: Int): Student =
  case std then s = std.name, sex = std.sex, age = std.age

// Usage
match student
case Name(n) => println("Name: " + n)
case Student(name, sex, age) =>
  println("Name: " + name + ", Sex: " + sex + ", Age: " + age)
end
```

## Multiple Cases

Pattern definitions can have multiple cases:

```jo
pattern Size[T](n: Int): List[T] =
  case Nil then n = 0
  case Cons(_, tail) then n = 1 + tail.size
  case Concat(lhs, rhs) then n = lhs.size + rhs.size

// Usage
match myList
case Size(n) if n > 10 => "Large list"
case Size(n) => "Small list"
end
```

## Generic Pattern Definitions

Pattern definitions can be generic:

```jo
pattern Head[T](x: T): List[T] =
  case [x, .._]

pattern Tail[T](xs: List[T]): List[T] =
  case [_, ..xs]

pattern NonEmpty[T]: List[T] =
  case [_, .._]
```

## Irrefutable and Partial Pattern Predicates

Pattern predicates can be either irrefutable (always match) or partial (may fail to match).

### Irrefutable Pattern Predicates

An irrefutable pattern predicate always matches its input type:

```jo
// Always matches - extracts name from Student
pattern Name(name: String): Student =
  case s then name = s.name

// Always matches - extracts all fields
pattern Student(s: String, sex: Bool, age: Int): Student =
  case std then s = std.name, sex = std.sex, age = std.age
```

### Partial Pattern Predicates

A partial pattern predicate may fail to match, indicated by the `Partial[T]` return type:

```jo
// May fail - only matches positive numbers
pattern Positive: Partial[Int] =
  case x if x > 0

// May fail - only matches even numbers
pattern Even: Partial[Int] =
  case x if x % 2 == 0

// May fail - only matches non-empty lists
pattern Head[T](x: T): Partial[List[T]] =
  case [x, .._]

// Usage in match expression
match n
case Positive => "positive"
case Even => "even"
case _ => "other"
end
```

The `Partial[T]` type indicates that the pattern may not match all values of type `T`.

## Parameter Binding Rule

Each pattern parameter must be definitely bound in each case of the pattern definition.

```jo
// ✓ OK - x bound in all cases
pattern Value[T](x: T): Option[T] =
  case Some(x)
  case None then x = defaultValue()

// ❌ Error - x not bound in all cases
pattern Invalid(x: Int): Option[Int] =
  case Some(x)
  case None  // x not bound here
```

## See Also

- [Pattern Forms](pattern-forms.md) - Pattern syntax
- [Pattern Language](index.md) - Complete pattern reference
