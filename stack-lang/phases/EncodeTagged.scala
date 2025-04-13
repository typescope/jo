package phases

import sast.*
import sast.Sast.*
import sast.Symbols.*

/** This phase encode tagged values as records */
class EncodeTagged extends Phase[Symbol]:
  val contextObject = Phase.OwnerContext

  override def transformTagged(tagged: TaggedLit)(using ctx: Context): Word =
    val tagType = tagged.tpe.asTagType
    val args2 = for arg <- tagged.args yield transform(arg)
    val encodedValue = TaggedEncoding.encodeVariant(tagType, args2, tagged.tagTree.span, tagged.span)
    Encoded(encodedValue)(tagType)

  override def transformSelect(select: Select)(using ctx: Context): Word =
    val qual2 = this(select.qual)
    val qualType = qual2.tpe
    if qualType.isTagType then
      val tagType = qualType.asTagType
      TaggedEncoding.selectVariantField(qual2, tagType, select.name, select.span)
    else
      Select(qual2, select.name)(select.tpe, select.span)
