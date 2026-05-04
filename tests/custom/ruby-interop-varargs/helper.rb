# Helper Ruby classes for @rb.interop vararg calling convention tests.
# Prepended to the compiled Jo output by test.sh.

# Used for ..Mixed[T]: accepts positional args and keyword args
class Logger
  def log(*parts, sep: " ", prefix: "")
    line = parts.map(&:to_s).join(sep)
    prefix.empty? ? line : "#{prefix}#{line}"
  end
end

# Used for ..Named[T]: accepts keyword-only args, returns "key=value" pairs sorted by key
class Configurator
  def configure(**opts)
    opts.map { |k, v| "#{k}=#{v}" }.sort.join(",")
  end
end
