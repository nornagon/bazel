// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.android.xml;

import static com.google.common.base.Predicates.equalTo;
import static com.google.common.base.Predicates.not;

import com.android.resources.ResourceType;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.Ordering;
import com.google.devtools.build.android.AndroidDataWritingVisitor;
import com.google.devtools.build.android.AndroidDataWritingVisitor.StartTag;
import com.google.devtools.build.android.AndroidDataWritingVisitor.ValuesResourceDefinition;
import com.google.devtools.build.android.AndroidResourceClassWriter;
import com.google.devtools.build.android.DataSource;
import com.google.devtools.build.android.FullyQualifiedName;
import com.google.devtools.build.android.XmlResourceValue;
import com.google.devtools.build.android.XmlResourceValues;
import com.google.devtools.build.android.proto.SerializeFormat;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

/**
 * Represents an Android Resource custom attribute.
 *
 * <p>Attribute are the most complicated Android resource, and therefore the least documented. Most
 * of the information about them is found by reading the android compatibility library source. An
 * Attribute defines a parameter that can be passed into a view class -- as such you can think of
 * attributes as creating slots for other resources to fit into. Each slot will have at least one
 * format, and can have multiples. Simple attributes (color, boolean, reference, dimension, float,
 * integer, string, and fraction) are defined as &lt;attr name="<em>name</em>" format=
 * "<em>format</em>" /&gt; while the complex ones, flag and enum, have sub parentTags: &lt;attr
 * name= "<em>name</em>" &gt&lt;flag name="<em>name</em>" value="<em>value</em>"&gt; &lt;/attr&gt;.
 *
 * <p>Attributes also have a double duty as defining validation logic for layout resources -- each
 * layout attribute *must* have a corresponding attribute which will be used to validate the
 * value/resource reference defined in it.
 *
 * <p>AttrXmlValue, due to the multiple types of attributes is actually a composite class that
 * contains multiple {@link XmlResourceValue} instances for each resource.
 */
@Immutable
public class AttrXmlResourceValue implements XmlResourceValue {

  private static final String FRACTION = "fraction";
  private static final String STRING = "string";
  private static final String INTEGER = "integer";
  private static final String FLOAT = "float";
  private static final String DIMENSION = "dimension";
  private static final String BOOLEAN = "boolean";
  private static final String COLOR = "color";
  private static final String REFERENCE = "reference";
  private static final String ENUM = "enum";
  private static final String FLAGS = "flags";
  private static final QName TAG_ENUM = QName.valueOf(ENUM);
  private static final QName TAG_FLAG = QName.valueOf("flag");
  private final ImmutableMap<String, ResourceXmlAttrValue> formats;

  private AttrXmlResourceValue(ImmutableMap<String, ResourceXmlAttrValue> formats) {
    this.formats = formats;
  }

  private static Map<String, String> readSubValues(XMLEventReader reader, QName subTagType)
      throws XMLStreamException {
    Builder<String, String> builder = ImmutableMap.builder();
    while (reader.hasNext()
        && XmlResourceValues.isTag(XmlResourceValues.peekNextTag(reader), subTagType)) {
      StartElement element = reader.nextEvent().asStartElement();
      builder.put(
          XmlResourceValues.getElementName(element), XmlResourceValues.getElementValue(element));
      XMLEvent endTag = reader.nextEvent();
      if (!XmlResourceValues.isEndTag(endTag, subTagType)) {
        throw new XMLStreamException(
            String.format("Unexpected [%s]; Expected %s", endTag, "</enum>"), endTag.getLocation());
      }
    }
    return builder.build();
  }

  private static void endAttrElement(XMLEventReader reader) throws XMLStreamException {
    XMLEvent endTag = reader.nextTag();
    if (!endTag.isEndElement() || !QName.valueOf("attr").equals(endTag.asEndElement().getName())) {
      throw new XMLStreamException("Unexpected ParentTag:" + endTag, endTag.getLocation());
    }
  }

  @VisibleForTesting
  private static final class BuilderEntry implements Entry<String, ResourceXmlAttrValue> {
    private final String name;
    private final ResourceXmlAttrValue value;

    BuilderEntry(String name, ResourceXmlAttrValue value) {
      this.name = name;
      this.value = value;
    }

    @Override
    public String getKey() {
      return name;
    }

    @Override
    public ResourceXmlAttrValue getValue() {
      return value;
    }

    @Override
    public ResourceXmlAttrValue setValue(ResourceXmlAttrValue value) {
      throw new UnsupportedOperationException();
    }
  }

  @SafeVarargs
  @VisibleForTesting
  public static XmlResourceValue fromFormatEntries(Entry<String, ResourceXmlAttrValue>... entries) {
    return of(ImmutableMap.copyOf(Arrays.asList(entries)));
  }

  @SuppressWarnings("deprecation")
  public static XmlResourceValue from(SerializeFormat.DataValueXml proto)
      throws InvalidProtocolBufferException {
    Builder<String, ResourceXmlAttrValue> formats =
        ImmutableMap.<String, AttrXmlResourceValue.ResourceXmlAttrValue>builder();
    for (Entry<String, SerializeFormat.DataValueXml> entry : proto.getMappedXmlValue().entrySet()) {
      switch (entry.getKey()) {
        case FLAGS:
          formats.put(
              entry.getKey(), FlagResourceXmlAttrValue.of(entry.getValue().getMappedStringValue()));
          break;
        case ENUM:
          formats.put(
              entry.getKey(), EnumResourceXmlAttrValue.of(entry.getValue().getMappedStringValue()));
          break;
        case REFERENCE:
          formats.put(entry.getKey(), ReferenceResourceXmlAttrValue.of());
          break;
        case COLOR:
          formats.put(entry.getKey(), ColorResourceXmlAttrValue.of());
          break;
        case BOOLEAN:
          formats.put(entry.getKey(), BooleanResourceXmlAttrValue.of());
          break;
        case DIMENSION:
          formats.put(entry.getKey(), DimensionResourceXmlAttrValue.of());
          break;
        case FLOAT:
          formats.put(entry.getKey(), FloatResourceXmlAttrValue.of());
          break;
        case INTEGER:
          formats.put(entry.getKey(), IntegerResourceXmlAttrValue.of());
          break;
        case STRING:
          formats.put(entry.getKey(), StringResourceXmlAttrValue.of());
          break;
        case FRACTION:
          formats.put(entry.getKey(), FractionResourceXmlAttrValue.of());
          break;
        default:
          throw new InvalidProtocolBufferException("Unexpected format: " + entry.getKey());
      }
    }
    return of(formats.build());
  }

  /**
   * Creates a new {@link AttrXmlResourceValue}. Returns null if there are no formats.
   */
  @Nullable
  public static XmlResourceValue from(
      StartElement attr, @Nullable String format, XMLEventReader eventReader)
      throws XMLStreamException {
    Set<String> formatNames = new HashSet<>();
    if (format != null) {
      Collections.addAll(formatNames, format.split("\\|"));
    }
    XMLEvent nextTag = XmlResourceValues.peekNextTag(eventReader);
    if (nextTag != null && nextTag.isStartElement()) {
      QName tagName = nextTag.asStartElement().getName();
      if (TAG_FLAG.equals(tagName)) {
        formatNames.add(FLAGS);
      } else {
        formatNames.add(tagName.getLocalPart().toLowerCase());
      }
    }

    Builder<String, ResourceXmlAttrValue> formats = ImmutableMap.builder();
    for (String formatName : formatNames) {
      switch (formatName) {
        case FLAGS:
          Map<String, String> flags = readSubValues(eventReader, TAG_FLAG);
          endAttrElement(eventReader);
          formats.put(formatName, FlagResourceXmlAttrValue.of(flags));
          break;
        case ENUM:
          Map<String, String> enums = readSubValues(eventReader, TAG_ENUM);
          endAttrElement(eventReader);
          formats.put(formatName, EnumResourceXmlAttrValue.of(enums));
          break;
        case REFERENCE:
          formats.put(formatName, ReferenceResourceXmlAttrValue.of());
          break;
        case COLOR:
          formats.put(formatName, ColorResourceXmlAttrValue.of());
          break;
        case BOOLEAN:
          formats.put(formatName, BooleanResourceXmlAttrValue.of());
          break;
        case DIMENSION:
          formats.put(formatName, DimensionResourceXmlAttrValue.of());
          break;
        case FLOAT:
          formats.put(formatName, FloatResourceXmlAttrValue.of());
          break;
        case INTEGER:
          formats.put(formatName, IntegerResourceXmlAttrValue.of());
          break;
        case STRING:
          formats.put(formatName, StringResourceXmlAttrValue.of());
          break;
        case FRACTION:
          formats.put(formatName, FractionResourceXmlAttrValue.of());
          break;
        default:
          throw new XMLStreamException(
              String.format("Unexpected attr format: %S", formatName), attr.getLocation());
      }
    }
    return of(formats.build());
  }

  public static XmlResourceValue of(ImmutableMap<String, ResourceXmlAttrValue> formats) {
    return new AttrXmlResourceValue(formats);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    AttrXmlResourceValue other = (AttrXmlResourceValue) o;
    return Objects.equals(formats, other.formats);
  }

  @Override
  public int hashCode() {
    return formats.hashCode();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("formats", formats).toString();
  }

  @Override
  public void write(
      FullyQualifiedName key, DataSource source, AndroidDataWritingVisitor mergedDataWriter) {

    if (formats.isEmpty()) {
      mergedDataWriter
          .define(key)
          .derivedFrom(source)
          .startTag("attr")
          .named(key)
          .closeUnaryTag()
          .save();
    } else {
      ImmutableList<String> formatKeys =
          FluentIterable.from(formats.keySet())
              .filter(not(equalTo(FLAGS)))
              .filter(not(equalTo(ENUM)))
              .toSortedList(Ordering.natural());
      StartTag startTag =
          mergedDataWriter
              .define(key)
              .derivedFrom(source)
              .startTag("attr")
              .named(key)
              .optional()
              .attribute("format")
              .setFrom(formatKeys)
              .joinedBy("|");
      ValuesResourceDefinition definition;
      if (formats.keySet().contains(FLAGS) || formats.keySet().contains(ENUM)) {
        definition = startTag.closeTag();
        for (ResourceXmlAttrValue value : formats.values()) {
          definition = value.writeTo(definition);
        }
        definition = definition.addCharactersOf("\n").endTag();
      } else {
        definition = startTag.closeUnaryTag();
      }
      definition.save();
    }
  }

  @Override
  public void writeResourceToClass(FullyQualifiedName key,
      AndroidResourceClassWriter resourceClassWriter) {
    resourceClassWriter.writeSimpleResource(key.type(), key.name());
    // Flags and enums generate ID fields.
    if (formats.keySet().contains(FLAGS) || formats.keySet().contains(ENUM)) {
      for (ResourceXmlAttrValue value : formats.values()) {
        value.writeToClass(resourceClassWriter);
      }
    }
  }

  @SuppressWarnings("deprecation")
  @Override
  public int serializeTo(int sourceId, Namespaces namespaces, OutputStream output)
      throws IOException {
    SerializeFormat.DataValue.Builder builder =
        XmlResourceValues.newSerializableDataValueBuilder(sourceId);
    SerializeFormat.DataValueXml.Builder xmlValueBuilder =
        SerializeFormat.DataValueXml.newBuilder();
    xmlValueBuilder
        .setType(SerializeFormat.DataValueXml.XmlType.ATTR)
        .putAllNamespace(namespaces.asMap());
    for (Entry<String, ResourceXmlAttrValue> entry : formats.entrySet()) {
      xmlValueBuilder.putMappedXmlValue(
          entry.getKey(), entry.getValue().appendTo(builder.getXmlValueBuilder()));
    }
    builder.setXmlValue(xmlValueBuilder);
    return XmlResourceValues.serializeProtoDataValue(output, builder);
  }

  @Override
  public XmlResourceValue combineWith(XmlResourceValue value) {
    throw new IllegalArgumentException(this + " is not a combinable resource.");
  }

  @CheckReturnValue
  interface ResourceXmlAttrValue {

    ValuesResourceDefinition writeTo(ValuesResourceDefinition writer);

    SerializeFormat.DataValueXml appendTo(SerializeFormat.DataValueXml.Builder builder);

    void writeToClass(AndroidResourceClassWriter writer);
  }

  // TODO(corysmith): The ResourceXmlAttrValue implementors, other than enum and flag, share a
  // lot of boilerplate. Determine how to reduce it.
  /** Represents an Android Enum Attribute resource. */
  @VisibleForTesting
  public static class EnumResourceXmlAttrValue implements ResourceXmlAttrValue {

    private Map<String, String> values;

    private EnumResourceXmlAttrValue(Map<String, String> values) {
      this.values = values;
    }

    @VisibleForTesting
    public static Entry<String, ResourceXmlAttrValue> asEntryOf(String... keyThenValue) {
      Preconditions.checkArgument(keyThenValue.length > 0);
      Preconditions.checkArgument(keyThenValue.length % 2 == 0);
      Builder<String, String> builder = ImmutableMap.builder();
      for (int i = 0; i < keyThenValue.length; i += 2) {
        builder.put(keyThenValue[i], keyThenValue[i + 1]);
      }
      return new BuilderEntry(ENUM, of(builder.build()));
    }

    public static ResourceXmlAttrValue of(Map<String, String> values) {
      return new EnumResourceXmlAttrValue(values);
    }

    @Override
    public int hashCode() {
      return values.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof EnumResourceXmlAttrValue)) {
        return false;
      }
      EnumResourceXmlAttrValue other = (EnumResourceXmlAttrValue) obj;
      return Objects.equals(values, other.values);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(getClass()).add("values", values).toString();
    }

    @Override
    public SerializeFormat.DataValueXml appendTo(SerializeFormat.DataValueXml.Builder builder) {
      return builder.putAllMappedStringValue(values).build();
    }

    @Override
    public ValuesResourceDefinition writeTo(ValuesResourceDefinition writer) {
      for (Entry<String, String> entry : values.entrySet()) {
        writer =
            writer
                .startTag("enum")
                .attribute("name")
                .setTo(entry.getKey())
                .attribute("value")
                .setTo(entry.getValue())
                .closeUnaryTag()
                .addCharactersOf("\n");
      }
      return writer;
    }

    @Override
    public void writeToClass(AndroidResourceClassWriter writer) {
      for (Map.Entry<String, String> entry : values.entrySet()) {
        writer.writeSimpleResource(ResourceType.ID, entry.getKey());
      }
    }
  }

  /** Represents an Android Flag Attribute resource. */
  @VisibleForTesting
  public static class FlagResourceXmlAttrValue implements ResourceXmlAttrValue {

    private Map<String, String> values;

    private FlagResourceXmlAttrValue(Map<String, String> values) {
      this.values = values;
    }

    public static ResourceXmlAttrValue of(Map<String, String> values) {
      // ImmutableMap guarantees a stable order.
      return new FlagResourceXmlAttrValue(ImmutableMap.copyOf(values));
    }

    @VisibleForTesting
    public static Entry<String, ResourceXmlAttrValue> asEntryOf(String... keyThenValue) {
      Builder<String, String> builder = ImmutableMap.builder();
      Preconditions.checkArgument(keyThenValue.length > 0);
      Preconditions.checkArgument(keyThenValue.length % 2 == 0);
      for (int i = 0; i < keyThenValue.length; i += 2) {
        builder.put(keyThenValue[i], keyThenValue[i + 1]);
      }
      return new BuilderEntry(FLAGS, of(builder.build()));
    }

    @Override
    public int hashCode() {
      return values.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof FlagResourceXmlAttrValue)) {
        return false;
      }
      FlagResourceXmlAttrValue other = (FlagResourceXmlAttrValue) obj;
      return Objects.equals(values, other.values);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(getClass()).add("values", values).toString();
    }

    @Override
    public SerializeFormat.DataValueXml appendTo(SerializeFormat.DataValueXml.Builder builder) {
      return builder.putAllMappedStringValue(values).build();
    }

    @Override
    public ValuesResourceDefinition writeTo(ValuesResourceDefinition writer) {
      for (Entry<String, String> entry : values.entrySet()) {
        writer =
            writer
                .startTag("flag")
                .attribute("name")
                .setTo(entry.getKey())
                .attribute("value")
                .setTo(entry.getValue())
                .closeUnaryTag();
      }
      return writer;
    }

    @Override
    public void writeToClass(AndroidResourceClassWriter writer) {
      for (Map.Entry<String, String> entry : values.entrySet()) {
        writer.writeSimpleResource(ResourceType.ID, entry.getKey());
      }
    }
  }

  /** Represents an Android Reference Attribute resource. */
  @VisibleForTesting
  public static class ReferenceResourceXmlAttrValue implements ResourceXmlAttrValue {
    private static final ReferenceResourceXmlAttrValue INSTANCE =
        new ReferenceResourceXmlAttrValue();

    public static ResourceXmlAttrValue of() {
      return INSTANCE;
    }

    @VisibleForTesting
    public static BuilderEntry asEntry() {
      return new BuilderEntry(REFERENCE, of());
    }

    @Override
    public SerializeFormat.DataValueXml appendTo(SerializeFormat.DataValueXml.Builder builder) {
      return builder.build();
    }

    @Override
    public ValuesResourceDefinition writeTo(ValuesResourceDefinition writer) {
      return writer;
    }

    @Override
    public void writeToClass(AndroidResourceClassWriter writer) {
    }
  }

  /** Represents an Android Color Attribute resource. */
  @VisibleForTesting
  public static class ColorResourceXmlAttrValue implements ResourceXmlAttrValue {
    private static final ColorResourceXmlAttrValue INSTANCE = new ColorResourceXmlAttrValue();

    public static ResourceXmlAttrValue of() {
      return INSTANCE;
    }

    @VisibleForTesting
    public static BuilderEntry asEntry() {
      return new BuilderEntry(COLOR, of());
    }

    @Override
    public SerializeFormat.DataValueXml appendTo(SerializeFormat.DataValueXml.Builder builder) {
      return builder.build();
    }

    @Override
    public ValuesResourceDefinition writeTo(ValuesResourceDefinition writer) {
      return writer;
    }

    @Override
    public void writeToClass(AndroidResourceClassWriter writer) {
    }
  }

  /** Represents an Android Boolean Attribute resource. */
  @VisibleForTesting
  public static class BooleanResourceXmlAttrValue implements ResourceXmlAttrValue {
    private static final BooleanResourceXmlAttrValue INSTANCE = new BooleanResourceXmlAttrValue();

    public static ResourceXmlAttrValue of() {
      return INSTANCE;
    }

    @VisibleForTesting
    public static BuilderEntry asEntry() {
      return new BuilderEntry(BOOLEAN, of());
    }

    @Override
    public SerializeFormat.DataValueXml appendTo(SerializeFormat.DataValueXml.Builder builder) {
      return builder.build();
    }

    @Override
    public ValuesResourceDefinition writeTo(ValuesResourceDefinition writer) {
      return writer;
    }

    @Override
    public void writeToClass(AndroidResourceClassWriter writer) {
    }
  }

  /** Represents an Android Float Attribute resource. */
  @VisibleForTesting
  public static class FloatResourceXmlAttrValue implements ResourceXmlAttrValue {
    private static final FloatResourceXmlAttrValue INSTANCE = new FloatResourceXmlAttrValue();

    public static ResourceXmlAttrValue of() {
      return INSTANCE;
    }

    @VisibleForTesting
    public static BuilderEntry asEntry() {
      return new BuilderEntry(FLOAT, of());
    }

    @Override
    public SerializeFormat.DataValueXml appendTo(SerializeFormat.DataValueXml.Builder builder) {
      return builder.build();
    }

    @Override
    public ValuesResourceDefinition writeTo(ValuesResourceDefinition writer) {
      return writer;
    }

    @Override
    public void writeToClass(AndroidResourceClassWriter writer) {
    }
  }

  /** Represents an Android Dimension Attribute resource. */
  @VisibleForTesting
  public static class DimensionResourceXmlAttrValue implements ResourceXmlAttrValue {
    private static final DimensionResourceXmlAttrValue INSTANCE =
        new DimensionResourceXmlAttrValue();

    public static ResourceXmlAttrValue of() {
      return INSTANCE;
    }

    @VisibleForTesting
    public static BuilderEntry asEntry() {
      return new BuilderEntry(DIMENSION, of());
    }

    @Override
    public SerializeFormat.DataValueXml appendTo(SerializeFormat.DataValueXml.Builder builder) {
      return builder.build();
    }

    @Override
    public ValuesResourceDefinition writeTo(ValuesResourceDefinition writer) {
      return writer;
    }

    @Override
    public void writeToClass(AndroidResourceClassWriter writer) {
    }
  }

  /** Represents an Android Integer Attribute resource. */
  @VisibleForTesting
  public static class IntegerResourceXmlAttrValue implements ResourceXmlAttrValue {
    private static final IntegerResourceXmlAttrValue INSTANCE = new IntegerResourceXmlAttrValue();

    public static ResourceXmlAttrValue of() {
      return INSTANCE;
    }

    @VisibleForTesting
    public static BuilderEntry asEntry() {
      return new BuilderEntry(INTEGER, of());
    }

    @Override
    public SerializeFormat.DataValueXml appendTo(SerializeFormat.DataValueXml.Builder builder) {
      return builder.build();
    }

    @Override
    public ValuesResourceDefinition writeTo(ValuesResourceDefinition writer) {
      return writer;
    }

    @Override
    public void writeToClass(AndroidResourceClassWriter writer) {
    }
  }

  /** Represents an Android String Attribute resource. */
  @VisibleForTesting
  public static class StringResourceXmlAttrValue implements ResourceXmlAttrValue {
    private static final StringResourceXmlAttrValue INSTANCE = new StringResourceXmlAttrValue();

    public static ResourceXmlAttrValue of() {
      return INSTANCE;
    }

    @VisibleForTesting
    public static BuilderEntry asEntry() {
      return new BuilderEntry(STRING, of());
    }

    @Override
    public SerializeFormat.DataValueXml appendTo(SerializeFormat.DataValueXml.Builder builder) {
      return builder.build();
    }

    @Override
    public ValuesResourceDefinition writeTo(ValuesResourceDefinition writer) {
      return writer;
    }

    @Override
    public void writeToClass(AndroidResourceClassWriter writer) {
    }
  }

  /** Represents an Android Fraction Attribute resource. */
  @VisibleForTesting
  public static class FractionResourceXmlAttrValue implements ResourceXmlAttrValue {
    private static final FractionResourceXmlAttrValue INSTANCE = new FractionResourceXmlAttrValue();

    public static ResourceXmlAttrValue of() {
      return INSTANCE;
    }

    @VisibleForTesting
    public static BuilderEntry asEntry() {
      return new BuilderEntry(FRACTION, of());
    }

    @Override
    public SerializeFormat.DataValueXml appendTo(SerializeFormat.DataValueXml.Builder builder) {
      return builder.build();
    }

    @Override
    public ValuesResourceDefinition writeTo(ValuesResourceDefinition writer) {
      return writer;
    }

    @Override
    public void writeToClass(AndroidResourceClassWriter writer) {
    }
  }

  @Override
  public String asConflictStringWith(DataSource source) {
    return source.asConflictString();
  }
}
