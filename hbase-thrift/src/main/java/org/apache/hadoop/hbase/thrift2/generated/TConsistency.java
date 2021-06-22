/**
 * Autogenerated by Thrift Compiler (0.13.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package org.apache.hadoop.hbase.thrift2.generated;


/**
 * Specify Consistency:
 *  - STRONG means reads only from primary region
 *  - TIMELINE means reads might return values from secondary region replicas
 */
@javax.annotation.Generated(value = "Autogenerated by Thrift Compiler (0.13.0)", date = "2021-02-09")
public enum TConsistency implements org.apache.thrift.TEnum {
  STRONG(1),
  TIMELINE(2);

  private final int value;

  private TConsistency(int value) {
    this.value = value;
  }

  /**
   * Get the integer value of this enum value, as defined in the Thrift IDL.
   */
  public int getValue() {
    return value;
  }

  /**
   * Find a the enum type by its integer value, as defined in the Thrift IDL.
   * @return null if the value is not found.
   */
  @org.apache.thrift.annotation.Nullable
  public static TConsistency findByValue(int value) { 
    switch (value) {
      case 1:
        return STRONG;
      case 2:
        return TIMELINE;
      default:
        return null;
    }
  }
}
