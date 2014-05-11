/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.common.util;

import java.math.BigDecimal;
import java.math.BigInteger;


import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBuf;

import java.math.BigDecimal;
import java.math.BigInteger;

public class DecimalUtility {

    public final static int MAX_DIGITS = 9;
    public final static int DIGITS_BASE = 1000000000;
    public final static int DIGITS_MAX = 999999999;
    public final static int integerSize = (Integer.SIZE/8);

    public final static String[] decimalToString = {"",
            "0",
            "00",
            "000",
            "0000",
            "00000",
            "000000",
            "0000000",
            "00000000",
            "000000000"};


    /* Given the number of actual digits this function returns the
     * number of indexes it will occupy in the array of integers
     * which are stored in base 1 billion
     */
    public static int roundUp(int ndigits) {
        return (ndigits + MAX_DIGITS - 1)/MAX_DIGITS;
    }

    /* Returns a string representation of the given integer
     * If the length of the given integer is less than the
     * passed length, this function will prepend zeroes to the string
     */
    public static StringBuilder toStringWithZeroes(int number, int desiredLength) {
        String value = ((Integer) number).toString();
        int length = value.length();

        StringBuilder str = new StringBuilder();
        str.append(decimalToString[desiredLength - length]);
        str.append(value);

        return str;
    }

    public static StringBuilder toStringWithZeroes(long number, int desiredLength) {
        String value = ((Long) number).toString();
        int length = value.length();

        StringBuilder str = new StringBuilder();

        // Desired length can be > MAX_DIGITS
        int zeroesLength = desiredLength - length;
        while (zeroesLength > MAX_DIGITS) {
            str.append(decimalToString[MAX_DIGITS]);
            zeroesLength -= MAX_DIGITS;
        }
        str.append(decimalToString[zeroesLength]);
        str.append(value);

        return str;
    }

    public static BigDecimal getBigDecimalFromIntermediate(ByteBuf data, int startIndex, int nDecimalDigits, int scale) {

        // In the intermediate representation we don't pad the scale with zeroes, so set truncate = false
        return getBigDecimalFromByteBuf(data, startIndex, nDecimalDigits, scale, false);
    }

    public static BigDecimal getBigDecimalFromSparse(ByteBuf data, int startIndex, int nDecimalDigits, int scale) {

        // In the sparse representation we pad the scale with zeroes for ease of arithmetic, need to truncate
        return getBigDecimalFromByteBuf(data, startIndex, nDecimalDigits, scale, true);
    }


    /* Create a BigDecimal object using the data in the ByteBuf.
     * This function assumes that data is provided in a non-dense format
     * It works on both sparse and intermediate representations.
     */
    public static BigDecimal getBigDecimalFromByteBuf(ByteBuf data, int startIndex, int nDecimalDigits, int scale, boolean truncateScale) {

        // For sparse decimal type we have padded zeroes at the end, strip them while converting to BigDecimal.
        int actualDigits;

        // Initialize the BigDecimal, first digit in the ByteBuf has the sign so mask it out
        BigInteger decimalDigits = BigInteger.valueOf((data.getInt(startIndex)) & 0x7FFFFFFF);

        BigInteger base = BigInteger.valueOf(DIGITS_BASE);

        for (int i = 1; i < nDecimalDigits; i++) {

            BigInteger temp = BigInteger.valueOf(data.getInt(startIndex + (i * integerSize)));
            decimalDigits = decimalDigits.multiply(base);
            decimalDigits = decimalDigits.add(temp);
        }

        // Truncate any additional padding we might have added
        if (truncateScale == true && scale > 0 && (actualDigits = scale % MAX_DIGITS) != 0) {
            BigInteger truncate = BigInteger.valueOf((int)Math.pow(10, (MAX_DIGITS - actualDigits)));
            decimalDigits = decimalDigits.divide(truncate);
        }

        // set the sign
        if ((data.getInt(startIndex) & 0x80000000) != 0) {
            decimalDigits = decimalDigits.negate();
        }

        BigDecimal decimal = new BigDecimal(decimalDigits, scale);

        return decimal;
    }

    /* This function returns a BigDecimal object from the dense decimal representation.
     * First step is to convert the dense representation into an intermediate representation
     * and then invoke getBigDecimalFromByteBuf() to get the BigDecimal object
     */
    public static BigDecimal getBigDecimalFromDense(ByteBuf data, int startIndex, int nDecimalDigits, int scale, int maxPrecision, int width) {

        /* This method converts the dense representation to
         * an intermediate representation. The intermediate
         * representation has one more integer than the dense
         * representation.
         */
        byte[] intermediateBytes = new byte[((nDecimalDigits + 1) * integerSize)];

        // Start storing from the least significant byte of the first integer
        int intermediateIndex = 3;

        int[] mask = {0x03, 0x0F, 0x3F, 0xFF};
        int[] reverseMask = {0xFC, 0xF0, 0xC0, 0x00};

        int maskIndex;
        int shiftOrder;
        byte shiftBits;

        // TODO: Some of the logic here is common with casting from Dense to Sparse types, factor out common code
        if (maxPrecision == 38) {
            maskIndex = 0;
            shiftOrder = 6;
            shiftBits = 0x00;
            intermediateBytes[intermediateIndex++] = (byte) (data.getByte(startIndex) & 0x7F);
        } else if (maxPrecision == 28) {
            maskIndex = 1;
            shiftOrder = 4;
            shiftBits = (byte) ((data.getByte(startIndex) & 0x03) << shiftOrder);
            intermediateBytes[intermediateIndex++] = (byte) (((data.getByte(startIndex) & 0x3C) & 0xFF) >>> 2);
        } else {
            throw new UnsupportedOperationException("Dense types with max precision 38 and 28 are only supported");
        }

        int inputIndex = 1;
        boolean sign = false;

        if ((data.getByte(startIndex) & 0x80) != 0) {
            sign = true;
        }

        while (inputIndex < width) {

            intermediateBytes[intermediateIndex] = (byte) ((shiftBits) | (((data.getByte(startIndex + inputIndex) & reverseMask[maskIndex]) & 0xFF) >>> (8 - shiftOrder)));

            shiftBits = (byte) ((data.getByte(startIndex + inputIndex) & mask[maskIndex]) << shiftOrder);

            inputIndex++;
            intermediateIndex++;

            if (((inputIndex - 1) % integerSize) == 0) {
                shiftBits = (byte) ((shiftBits & 0xFF) >>> 2);
                maskIndex++;
                shiftOrder -= 2;
            }

        }
        /* copy the last byte */
        intermediateBytes[intermediateIndex] = shiftBits;

        if (sign == true) {
            intermediateBytes[0] = (byte) (intermediateBytes[0] | 0x80);
        }

        ByteBuf intermediateData = Unpooled.wrappedBuffer(intermediateBytes);

        return getBigDecimalFromIntermediate(intermediateData, 0, nDecimalDigits + 1, scale);
    }

    /*
     * Function converts the BigDecimal and stores it in out internal sparse representation
     */
    public static void getSparseFromBigDecimal(BigDecimal input, ByteBuf data, int startIndex, int scale, int precision, int nDecimalDigits) {

        boolean sign = false;

        if (input.signum() == -1) {
            // negative input
            sign = true;
            input = input.abs();
        }

        // Truncate the input as per the scale provided
        input = input.setScale(scale, BigDecimal.ROUND_DOWN);

        // Separate out the integer part
        BigDecimal integerPart = input.setScale(0, BigDecimal.ROUND_DOWN);

        int destIndex = nDecimalDigits - roundUp(scale) - 1;

        // we use base 1 billion integer digits for out integernal representation
        BigDecimal base = new BigDecimal(DIGITS_BASE);

        while (integerPart.compareTo(BigDecimal.ZERO) == 1) {
            // store the modulo as the integer value
            data.setInt(startIndex + (destIndex * integerSize), (integerPart.remainder(base)).intValue());
            destIndex--;
            // Divide by base 1 billion
            integerPart = (integerPart.divide(base)).setScale(0, BigDecimal.ROUND_DOWN);
        }

        /* Sparse representation contains padding of additional zeroes
         * so each digit contains MAX_DIGITS for ease of arithmetic
         */
        int actualDigits;
        if ((actualDigits = (scale % MAX_DIGITS)) != 0) {
            // Pad additional zeroes
            scale = scale + (MAX_DIGITS - actualDigits);
            input = input.setScale(scale, BigDecimal.ROUND_DOWN);
        }

        //separate out the fractional part
        BigDecimal fractionalPart = input.remainder(BigDecimal.ONE).movePointRight(scale);

        destIndex = nDecimalDigits - 1;

        while (scale > 0) {
            // Get next set of MAX_DIGITS (9) store it in the ByteBuf
            fractionalPart = fractionalPart.movePointLeft(MAX_DIGITS);
            BigDecimal temp = fractionalPart.remainder(BigDecimal.ONE);

            data.setInt(startIndex + (destIndex * integerSize), (temp.unscaledValue().intValue()));
            destIndex--;

            fractionalPart = fractionalPart.setScale(0, BigDecimal.ROUND_DOWN);
            scale -= MAX_DIGITS;
        }

        // Set the negative sign
        if (sign == true) {
            data.setInt(startIndex, data.getInt(startIndex) | 0x80000000);
        }

    }
    public static int getDecimal9FromBigDecimal(BigDecimal input, int scale, int precision) {
        // Truncate/ or pad to set the input to the correct scale
        input = input.setScale(scale, BigDecimal.ROUND_DOWN);

        return (input.unscaledValue().intValue());
    }

    public static long getDecimal18FromBigDecimal(BigDecimal input, int scale, int precision) {
        // Truncate or pad to set the input to the correct scale
        input = input.setScale(scale, BigDecimal.ROUND_DOWN);

        return (input.unscaledValue().longValue());
    }

    public static int compareDenseBytes(ByteBuf left, int leftStart, boolean leftSign, ByteBuf right, int rightStart, boolean rightSign, int width) {

      int invert = 1;

      /* If signs are different then simply look at the
       * sign of the two inputs and determine which is greater
       */
      if (leftSign != rightSign) {

        return((leftSign == true) ? -1 : 1);
      } else if(leftSign == true) {
        /* Both inputs are negative, at the end we will
         * have to invert the comparison
         */
        invert = -1;
      }

      int cmp = 0;

      for (int i = 0; i < width; i++) {
        byte leftByte  = left.getByte(leftStart + i);
        byte rightByte = right.getByte(rightStart + i);
        // Unsigned byte comparison
        if ((leftByte & 0xFF) > (rightByte & 0xFF)) {
          cmp = 1;
          break;
        } else if ((leftByte & 0xFF) < (rightByte & 0xFF)) {
          cmp = -1;
          break;
        }
      }
      cmp *= invert; // invert the comparison if both were negative values

      return cmp;
    }

    public static int getIntegerFromSparseBuffer(ByteBuf buffer, int start, int index) {
      int value = buffer.getInt(start + (index * 4));

      if (index == 0) {
        /* the first byte contains sign bit, return value without it */
        value = (value & 0x7FFFFFFF);
      }
      return value;
    }

    public static void setInteger(ByteBuf buffer, int start, int index, int value) {
      buffer.setInt(start + (index * 4), value);
    }

    public static int compareSparseBytes(ByteBuf left, int leftStart, boolean leftSign, int leftScale, int leftPrecision, ByteBuf right, int rightStart, boolean rightSign, int rightPrecision, int rightScale, int width, int nDecimalDigits, boolean absCompare) {

      int invert = 1;

      if (absCompare == false) {
        if (leftSign != rightSign) {
          return (leftSign == true) ? -1 : 1;
        }

        // Both values are negative invert the outcome of the comparison
        if (leftSign == true) {
          invert = -1;
        }
      }

      int cmp = compareSparseBytesInner(left, leftStart, leftSign, leftScale, leftPrecision, right, rightStart, rightSign, rightPrecision, rightScale, width, nDecimalDigits);
      return cmp * invert;
    }
    public static int compareSparseBytesInner(ByteBuf left, int leftStart, boolean leftSign, int leftScale, int leftPrecision, ByteBuf right, int rightStart, boolean rightSign, int rightPrecision, int rightScale, int width, int nDecimalDigits) {
      /* compute the number of integer digits in each decimal */
      int leftInt  = leftPrecision - leftScale;
      int rightInt = rightPrecision - rightScale;

      /* compute the number of indexes required for storing integer digits */
      int leftIntRoundedUp = org.apache.drill.common.util.DecimalUtility.roundUp(leftInt);
      int rightIntRoundedUp = org.apache.drill.common.util.DecimalUtility.roundUp(rightInt);

      /* compute number of indexes required for storing scale */
      int leftScaleRoundedUp = org.apache.drill.common.util.DecimalUtility.roundUp(leftScale);
      int rightScaleRoundedUp = org.apache.drill.common.util.DecimalUtility.roundUp(rightScale);

      /* compute index of the most significant integer digits */
      int leftIndex1 = nDecimalDigits - leftScaleRoundedUp - leftIntRoundedUp;
      int rightIndex1 = nDecimalDigits - rightScaleRoundedUp - rightIntRoundedUp;

      int leftStopIndex = nDecimalDigits - leftScaleRoundedUp;
      int rightStopIndex = nDecimalDigits - rightScaleRoundedUp;

      /* Discard the zeroes in the integer part */
      while (leftIndex1 < leftStopIndex) {
        if (getIntegerFromSparseBuffer(left, leftStart, leftIndex1) != 0) {
          break;
        }

        /* Digit in this location is zero, decrement the actual number
         * of integer digits
         */
        leftIntRoundedUp--;
        leftIndex1++;
      }

      /* If we reached the stop index then the number of integers is zero */
      if (leftIndex1 == leftStopIndex) {
        leftIntRoundedUp = 0;
      }

      while (rightIndex1 < rightStopIndex) {
        if (getIntegerFromSparseBuffer(right, rightStart, rightIndex1) != 0) {
          break;
        }

        /* Digit in this location is zero, decrement the actual number
         * of integer digits
         */
        rightIntRoundedUp--;
        rightIndex1++;
      }

      if (rightIndex1 == rightStopIndex) {
        rightIntRoundedUp = 0;
      }

      /* We have the accurate number of non-zero integer digits,
       * if the number of integer digits are different then we can determine
       * which decimal is larger and needn't go down to comparing individual values
       */
      if (leftIntRoundedUp > rightIntRoundedUp) {
        return 1;
      }
      else if (rightIntRoundedUp > leftIntRoundedUp) {
        return -1;
      }

      /* The number of integer digits are the same, set the each index
       * to the first non-zero integer and compare each digit
       */
      leftIndex1 = nDecimalDigits - leftScaleRoundedUp - leftIntRoundedUp;
      rightIndex1 = nDecimalDigits - rightScaleRoundedUp - rightIntRoundedUp;

      while (leftIndex1 < leftStopIndex && rightIndex1 < rightStopIndex) {
        if (getIntegerFromSparseBuffer(left, leftStart, leftIndex1) > getIntegerFromSparseBuffer(right, rightStart, rightIndex1)) {
          return 1;
        }
        else if (getIntegerFromSparseBuffer(right, rightStart, rightIndex1) > getIntegerFromSparseBuffer(left, leftStart, leftIndex1)) {
          return -1;
        }

        leftIndex1++;
        rightIndex1++;
      }

      /* The integer part of both the decimal's are equal, now compare
       * each individual fractional part. Set the index to be at the
       * beginning of the fractional part
       */
      leftIndex1 = leftStopIndex;
      rightIndex1 = rightStopIndex;

      /* Stop indexes will be the end of the array */
      leftStopIndex = nDecimalDigits;
      rightStopIndex = nDecimalDigits;

      /* compare the two fractional parts of the decimal */
      while (leftIndex1 < leftStopIndex && rightIndex1 < rightStopIndex) {
        if (getIntegerFromSparseBuffer(left, leftStart, leftIndex1) > getIntegerFromSparseBuffer(right, rightStart, rightIndex1)) {
          return 1;
        }
        else if (getIntegerFromSparseBuffer(right, rightStart, rightIndex1) > getIntegerFromSparseBuffer(left, leftStart, leftIndex1)) {
          return -1;
        }

        leftIndex1++;
        rightIndex1++;
      }

      /* Till now the fractional part of the decimals are equal, check
       * if one of the decimal has fractional part that is remaining
       * and is non-zero
       */
      while (leftIndex1 < leftStopIndex) {
        if (getIntegerFromSparseBuffer(left, leftStart, leftIndex1) != 0) {
          return 1;
        }
        leftIndex1++;
      }

      while(rightIndex1 < rightStopIndex) {
        if (getIntegerFromSparseBuffer(right, rightStart, rightIndex1) != 0) {
          return -1;
        }
        rightIndex1++;
      }

      /* Both decimal values are equal */
      return 0;
    }

}
