/**
 * Autogenerated by Thrift Compiler (0.7.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 */
package org.apache.hadoop.corona;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.Set;
import java.util.HashSet;
import java.util.EnumSet;
import java.util.Collections;
import java.util.BitSet;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InvalidSessionHandle extends Exception implements org.apache.thrift.TBase<InvalidSessionHandle, InvalidSessionHandle._Fields>, java.io.Serializable, Cloneable {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("InvalidSessionHandle");

  private static final org.apache.thrift.protocol.TField HANDLE_FIELD_DESC = new org.apache.thrift.protocol.TField("handle", org.apache.thrift.protocol.TType.STRING, (short)1);

  public String handle; // required

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    HANDLE((short)1, "handle");

    private static final Map<String, _Fields> byName = new HashMap<String, _Fields>();

    static {
      for (_Fields field : EnumSet.allOf(_Fields.class)) {
        byName.put(field.getFieldName(), field);
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, or null if its not found.
     */
    public static _Fields findByThriftId(int fieldId) {
      switch(fieldId) {
        case 1: // HANDLE
          return HANDLE;
        default:
          return null;
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, throwing an exception
     * if it is not found.
     */
    public static _Fields findByThriftIdOrThrow(int fieldId) {
      _Fields fields = findByThriftId(fieldId);
      if (fields == null) throw new IllegalArgumentException("Field " + fieldId + " doesn't exist!");
      return fields;
    }

    /**
     * Find the _Fields constant that matches name, or null if its not found.
     */
    public static _Fields findByName(String name) {
      return byName.get(name);
    }

    private final short _thriftId;
    private final String _fieldName;

    _Fields(short thriftId, String fieldName) {
      _thriftId = thriftId;
      _fieldName = fieldName;
    }

    public short getThriftFieldId() {
      return _thriftId;
    }

    public String getFieldName() {
      return _fieldName;
    }
  }

  // isset id assignments

  public static final Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.HANDLE, new org.apache.thrift.meta_data.FieldMetaData("handle", org.apache.thrift.TFieldRequirementType.REQUIRED, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(InvalidSessionHandle.class, metaDataMap);
  }

  public InvalidSessionHandle() {
  }

  public InvalidSessionHandle(
    String handle)
  {
    this();
    this.handle = handle;
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public InvalidSessionHandle(InvalidSessionHandle other) {
    if (other.isSetHandle()) {
      this.handle = other.handle;
    }
  }

  public InvalidSessionHandle deepCopy() {
    return new InvalidSessionHandle(this);
  }

  @Override
  public void clear() {
    this.handle = null;
  }

  public String getHandle() {
    return this.handle;
  }

  public InvalidSessionHandle setHandle(String handle) {
    this.handle = handle;
    return this;
  }

  public void unsetHandle() {
    this.handle = null;
  }

  /** Returns true if field handle is set (has been assigned a value) and false otherwise */
  public boolean isSetHandle() {
    return this.handle != null;
  }

  public void setHandleIsSet(boolean value) {
    if (!value) {
      this.handle = null;
    }
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case HANDLE:
      if (value == null) {
        unsetHandle();
      } else {
        setHandle((String)value);
      }
      break;

    }
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case HANDLE:
      return getHandle();

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case HANDLE:
      return isSetHandle();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof InvalidSessionHandle)
      return this.equals((InvalidSessionHandle)that);
    return false;
  }

  public boolean equals(InvalidSessionHandle that) {
    if (that == null)
      return false;

    boolean this_present_handle = true && this.isSetHandle();
    boolean that_present_handle = true && that.isSetHandle();
    if (this_present_handle || that_present_handle) {
      if (!(this_present_handle && that_present_handle))
        return false;
      if (!this.handle.equals(that.handle))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    return 0;
  }

  public int compareTo(InvalidSessionHandle other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;
    InvalidSessionHandle typedOther = (InvalidSessionHandle)other;

    lastComparison = Boolean.valueOf(isSetHandle()).compareTo(typedOther.isSetHandle());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetHandle()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.handle, typedOther.handle);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    return 0;
  }

  public _Fields fieldForId(int fieldId) {
    return _Fields.findByThriftId(fieldId);
  }

  public void read(org.apache.thrift.protocol.TProtocol iprot) throws org.apache.thrift.TException {
    org.apache.thrift.protocol.TField field;
    iprot.readStructBegin();
    while (true)
    {
      field = iprot.readFieldBegin();
      if (field.type == org.apache.thrift.protocol.TType.STOP) { 
        break;
      }
      switch (field.id) {
        case 1: // HANDLE
          if (field.type == org.apache.thrift.protocol.TType.STRING) {
            this.handle = iprot.readString();
          } else { 
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
          }
          break;
        default:
          org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
      }
      iprot.readFieldEnd();
    }
    iprot.readStructEnd();

    // check for required fields of primitive type, which can't be checked in the validate method
    validate();
  }

  public void write(org.apache.thrift.protocol.TProtocol oprot) throws org.apache.thrift.TException {
    validate();

    oprot.writeStructBegin(STRUCT_DESC);
    if (this.handle != null) {
      oprot.writeFieldBegin(HANDLE_FIELD_DESC);
      oprot.writeString(this.handle);
      oprot.writeFieldEnd();
    }
    oprot.writeFieldStop();
    oprot.writeStructEnd();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("InvalidSessionHandle(");
    boolean first = true;

    sb.append("handle:");
    if (this.handle == null) {
      sb.append("null");
    } else {
      sb.append(this.handle);
    }
    first = false;
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    if (handle == null) {
      throw new org.apache.thrift.protocol.TProtocolException("Required field 'handle' was not present! Struct: " + toString());
    }
  }

  private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
    try {
      write(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(out)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
    try {
      read(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(in)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

}
