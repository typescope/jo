package phases

import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

/** This phase encode tagged values as records */
class EncodeTagged extends Phase[Symbol]:
  val contextObject = Phase.OwnerContext

  override def transformTagged(tagged: TaggedLit)(using ctx: Context): Word =
    val tagType = tagged.tpe.asTagType
    val encodedValue = TaggedEncoding.encodeVariant(tagType, tagged.args, tagged.tag.span, tagged.span)
    Encoded(encodedValue)(tagType)

  override def transformSelect(select: Select)(using ctx: Context): Word =
    val qual2 = this(select.qual)
    val qualType = qual2.tpe
    if qualType.isTagType then
      val tagType = qualType.asTagType
      TaggedEncoding.selectVariantField(qual2, tagType, select.name, select.span)
    else
      select
