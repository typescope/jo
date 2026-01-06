# Pattern Definitions

Pattern definitions create reusable, named patterns that can be used in `match` expressions and other pattern contexts.


For comprehensive coverage of pattern definitions including advanced features, semantics, and detailed examples, see:

- [Pattern Forms](../patterns/pattern-forms.md) - Pattern syntax and composition
- [Pattern Definitions](../patterns/pattern-definitions.md) - Complete specification
- [Algebraic Data Types](adt.md) - Types designed for pattern matching

## Examples

Define patterns that match specific conditions:

```jo
// A pattern for positive numbers
pattern Positive: Int =
  case x if x > 0

// A pattern for even numbers
pattern Even: Int =
  case x if x % 2 == 0

// Use in match
match n
case Positive & Even => "positive even"
case Positive => "positive odd"
case Even => "non-positive even"
case _ => "non-positive odd"
end
```

Patterns can have parameters to extract computed values:

```jo
// Pattern that extracts list size
pattern Size[T](n: Int): List[T] =
  case Nil then n = 0
  case Cons(_, tail) then n = 1 + tail.size

// Use the extracted value
match myList
case Size(n) if n > 10 => "large list"
case Size(n) => "small list"
end
```
