# Dropping Values

An expression produces a value, It is a common programming mistake to silently
drop a value. The compiler warns if a non-Unit value is silently dropped:

```jo
fact(10)   // warning: value of type Int is silently dropped
```

To acknowledge an intentional discard, use:

```jo
val _ = fact(10)   // no warning
```

A function may also specify that its result may be silently dropped, which is
common for the [builder pattern](https://en.wikipedia.org/wiki/Builder_pattern):

```jo
class AgentBuilder
  @discardableResult
  def model(m: String): AgentBuilder = ...

  @discardableResult
  def maxTokens(n: Int): AgentBuilder = ...

  def build: Agent = ...
end

// statement style
builder.maxTokens(200)    // OK, marked as @discardableResult
builder.model("gpt-5.6")
val agent = builder.build

// chaining style
val agent= builder
  .maxTokens(200)
  .model("gpt-5.6")
  .build
```

::: warning Ordinary mutation should still return `Unit`

`@discardableResult` earns its place where chaining is the point of the API, as
in the builder above. A method that merely mutates a collection should return
`Unit` instead:

```jo
s.add 1     // `mutable.Set.add` returns Unit
s += 1      // the operator form, for the same job
```

Returning `this` from a mutating method makes the call site read exactly like
the pure operation of the same name. `val s2 = s.add 1` binds the *same* set
when `s` is a `mutable.Set`, but a *new* one when `s` is the immutable `Set` —
and nothing at the call site shows which. Leaving mutation at `Unit` keeps that
difference visible in the types.

:::

::: info The exceptions for silently dropping a value

The rule allows the following exceptions:

- The value is of the type `Unit` or `Bottom`
- The value is produced by a call where the function is marked `@discardableResult`
- The value has a member `callDynamic` or `selectDynamic`

:::
