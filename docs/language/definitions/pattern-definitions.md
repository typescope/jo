# Pattern Definitions

Pattern definitions create reusable pattern functions for matching and extracting data.

## Syntax

```jo
pattern Name: Type =
  case pattern

pattern Name(param1: Type1, param2: Type2): Type =
  case pattern
```

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

## Examples

### Validation Patterns

```jo
pattern ValidEmail(email: String): String =
  case s if s.contains("@") && s.contains(".") then email = s

pattern ValidAge(age: Int): Int =
  case n if n >= 0 && n <= 150 then age = n

match userInput
case ValidEmail(email) =>
  saveEmail(email)
case _ =>
  showError("Invalid email")
end
```

### Classification Patterns

```jo
union Category = Small | Medium | Large

pattern Categorize(cat: Category): Int =
  case x then cat = begin
    if x < 10 then Small
    else if x < 100 then Medium
    else Large
  end

match value
case Categorize Small => "Small value"
case Categorize Medium => "Medium value"
case Categorize Large => "Large value"
end
```

### Complex Extraction

```jo
pattern FullName(first: String, last: String): User =
  case User(name) then
    first = name.split(" ")[0],
    last = name.split(" ")[1]

pattern Adult(name: String, age: Int): User =
  case u if u.age >= 18 then name = u.name, age = u.age

match user
case Adult(name, age) =>
  println("Adult: " + name + ", age: " + age)
case _ =>
  println("Not an adult")
end
```

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

- [Pattern Forms](../patterns/pattern-forms.md) - Pattern syntax
- [Pattern Definitions (Patterns)](../patterns/pattern-definitions.md) - Detailed semantics
- [Pattern Language](../patterns/index.md) - Complete pattern reference
