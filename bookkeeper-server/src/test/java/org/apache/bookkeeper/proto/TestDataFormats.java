// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: src/test/proto/TestDataFormats.proto

package org.apache.bookkeeper.proto;

public final class TestDataFormats {
  private TestDataFormats() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registry.add(org.apache.bookkeeper.proto.TestDataFormats.messageType);
  }
  public enum AuthMessageType
      implements com.google.protobuf.ProtocolMessageEnum {
    SUCCESS_RESPONSE(0, 1),
    FAILURE_RESPONSE(1, 2),
    PAYLOAD_MESSAGE(2, 3),
    ;
    
    public static final int SUCCESS_RESPONSE_VALUE = 1;
    public static final int FAILURE_RESPONSE_VALUE = 2;
    public static final int PAYLOAD_MESSAGE_VALUE = 3;
    
    
    public final int getNumber() { return value; }
    
    public static AuthMessageType valueOf(int value) {
      switch (value) {
        case 1: return SUCCESS_RESPONSE;
        case 2: return FAILURE_RESPONSE;
        case 3: return PAYLOAD_MESSAGE;
        default: return null;
      }
    }
    
    public static com.google.protobuf.Internal.EnumLiteMap<AuthMessageType>
        internalGetValueMap() {
      return internalValueMap;
    }
    private static com.google.protobuf.Internal.EnumLiteMap<AuthMessageType>
        internalValueMap =
          new com.google.protobuf.Internal.EnumLiteMap<AuthMessageType>() {
            public AuthMessageType findValueByNumber(int number) {
              return AuthMessageType.valueOf(number);
            }
          };
    
    public final com.google.protobuf.Descriptors.EnumValueDescriptor
        getValueDescriptor() {
      return getDescriptor().getValues().get(index);
    }
    public final com.google.protobuf.Descriptors.EnumDescriptor
        getDescriptorForType() {
      return getDescriptor();
    }
    public static final com.google.protobuf.Descriptors.EnumDescriptor
        getDescriptor() {
      return org.apache.bookkeeper.proto.TestDataFormats.getDescriptor().getEnumTypes().get(0);
    }
    
    private static final AuthMessageType[] VALUES = {
      SUCCESS_RESPONSE, FAILURE_RESPONSE, PAYLOAD_MESSAGE, 
    };
    
    public static AuthMessageType valueOf(
        com.google.protobuf.Descriptors.EnumValueDescriptor desc) {
      if (desc.getType() != getDescriptor()) {
        throw new java.lang.IllegalArgumentException(
          "EnumValueDescriptor is not for this type.");
      }
      return VALUES[desc.getIndex()];
    }
    
    private final int index;
    private final int value;
    
    private AuthMessageType(int index, int value) {
      this.index = index;
      this.value = value;
    }
    
    // @@protoc_insertion_point(enum_scope:AuthMessageType)
  }
  
  public static final int MESSAGETYPE_FIELD_NUMBER = 1000;
  public static final
    com.google.protobuf.GeneratedMessage.GeneratedExtension<
      org.apache.bookkeeper.proto.DataFormats.AuthMessage,
      org.apache.bookkeeper.proto.TestDataFormats.AuthMessageType> messageType = com.google.protobuf.GeneratedMessage
          .newFileScopedGeneratedExtension(
        org.apache.bookkeeper.proto.TestDataFormats.AuthMessageType.class,
        null);
  
  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n$src/test/proto/TestDataFormats.proto\032 " +
      "src/main/proto/DataFormats.proto*R\n\017Auth" +
      "MessageType\022\024\n\020SUCCESS_RESPONSE\020\001\022\024\n\020FAI" +
      "LURE_RESPONSE\020\002\022\023\n\017PAYLOAD_MESSAGE\020\003:4\n\013" +
      "messageType\022\014.AuthMessage\030\350\007 \002(\0162\020.AuthM" +
      "essageTypeB\037\n\033org.apache.bookkeeper.prot" +
      "oH\001"
    };
    com.google.protobuf.Descriptors.FileDescriptor.InternalDescriptorAssigner assigner =
      new com.google.protobuf.Descriptors.FileDescriptor.InternalDescriptorAssigner() {
        public com.google.protobuf.ExtensionRegistry assignDescriptors(
            com.google.protobuf.Descriptors.FileDescriptor root) {
          descriptor = root;
          messageType.internalInit(descriptor.getExtensions().get(0));
          return null;
        }
      };
    com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
          org.apache.bookkeeper.proto.DataFormats.getDescriptor(),
        }, assigner);
  }
  
  // @@protoc_insertion_point(outer_class_scope)
}