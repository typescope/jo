# Helper Ruby classes that use keyword-only parameters.
# These are loaded by test.sh and prepended to the compiled Jo output.

class Formatter
  # format() has keyword-only parameters width: and align:
  def format(template, width: 8, align: "left")
    case align
    when "left"  then template.ljust(width)
    when "right" then template.rjust(width)
    else template
    end
  end
end

class Classifier
  # classify() has a keyword-only parameter named `type:`.
  # `type` is a valid Ruby identifier but a Jo keyword, so the Jo wrapper
  # must rename it via @rb.keyword("type").
  def classify(value, type: "unknown")
    "#{value}:#{type}"
  end
end
