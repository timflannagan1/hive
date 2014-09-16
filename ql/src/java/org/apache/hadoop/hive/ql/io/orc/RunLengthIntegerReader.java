/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.io.orc;

import java.io.EOFException;
import java.io.IOException;

import org.apache.hadoop.hive.llap.api.Vector;
import org.apache.hadoop.hive.llap.api.Vector.Type;
import org.apache.hadoop.hive.llap.chunk.ChunkWriter;
import org.apache.hadoop.hive.llap.chunk.ChunkWriter.NullsState;
import org.apache.hadoop.hive.ql.exec.vector.LongColumnVector;
import org.apache.hadoop.hive.ql.io.orc.LlapUtils.PresentStreamReadResult;

/**
 * A reader that reads a sequence of integers.
 * */
class RunLengthIntegerReader implements IntegerReader {
  private final InStream input;
  private final boolean signed;
  private final long[] literals =
    new long[RunLengthIntegerWriter.MAX_LITERAL_SIZE];
  private int numLiterals = 0;
  private int delta = 0;
  private int used = 0;
  private boolean repeat = false;
  private SerializationUtils utils;

  RunLengthIntegerReader(InStream input, boolean signed) throws IOException {
    this.input = input;
    this.signed = signed;
    this.utils = new SerializationUtils();
  }

  private void readValues(boolean ignoreEof) throws IOException {
    int control = input.read();
    if (control == -1) {
      if (!ignoreEof) {
        throw new EOFException("Read past end of RLE integer from " + input);
      }
      used = numLiterals = 0;
      return;
    } else if (control < 0x80) {
      numLiterals = control + RunLengthIntegerWriter.MIN_REPEAT_SIZE;
      used = 0;
      repeat = true;
      delta = input.read();
      if (delta == -1) {
        throw new EOFException("End of stream in RLE Integer from " + input);
      }
      // convert from 0 to 255 to -128 to 127 by converting to a signed byte
      delta = (byte) (0 + delta);
      if (signed) {
        literals[0] = utils.readVslong(input);
      } else {
        literals[0] = utils.readVulong(input);
      }
    } else {
      repeat = false;
      numLiterals = 0x100 - control;
      used = 0;
      for(int i=0; i < numLiterals; ++i) {
        if (signed) {
          literals[i] = utils.readVslong(input);
        } else {
          literals[i] = utils.readVulong(input);
        }
      }
    }
  }

  @Override
  public boolean hasNext() throws IOException {
    return used != numLiterals || input.available() > 0;
  }

  @Override
  public long next() throws IOException {
    long result;
    if (used == numLiterals) {
      readValues(false);
    }
    if (repeat) {
      result = literals[0] + (used++) * delta;
    } else {
      result = literals[used++];
    }
    return result;
  }

  private final PresentStreamReadResult presentHelper = new PresentStreamReadResult();
  @Override
  public int nextChunk(
      ChunkWriter writer, BitFieldReader present, long rowsLeftToRead) throws IOException {
    boolean mayHaveNulls = present != null;
    int rowsLeftToWrite = writer.estimateValueCountThatFits(Type.LONG, mayHaveNulls);
    if (rowsLeftToWrite == 0) {
      return 0; // Cannot write any rows into this writer.
    }
    long originalRowsLeft = rowsLeftToRead;
    // Start the big loop to read rows until we run out of either input or space.
    while (rowsLeftToRead > 0 && rowsLeftToWrite > 0) {
      int rowsToTransfer = (int)Math.min(rowsLeftToRead, rowsLeftToWrite);
      presentHelper.availLength = Math.min(peekNextAvailLength(), rowsToTransfer);
      if (mayHaveNulls) {
        LlapUtils.readPresentStream(presentHelper, present, rowsToTransfer);
      }
      assert presentHelper.availLength > 0;
      assert rowsLeftToRead >= presentHelper.availLength;

      if (presentHelper.isNullsRun) {
        writer.writeNulls(presentHelper.availLength, presentHelper.isFollowedByOther);
      } else {
        NullsState nullsState = !mayHaveNulls ? NullsState.NO_NULLS :
              (presentHelper.isFollowedByOther ? NullsState.NEXT_NULL : NullsState.HAS_NULLS);
        if (repeat && delta == 0) {
          writer.writeRepeatedLongs(literals[0], presentHelper.availLength, nullsState);
        } else {
          writer.writeLongs(literals, used, presentHelper.availLength, nullsState);
        }
        skipCurrentLiterals(presentHelper.availLength);
      }
      rowsLeftToWrite = writer.estimateValueCountThatFits(Type.LONG, mayHaveNulls);
      rowsLeftToRead -= presentHelper.availLength;
    } // End of big loop.
    writer.finishCurrentSegment();
    return (int)(originalRowsLeft - rowsLeftToRead);
  }

  private int peekNextAvailLength() throws IOException {
    if (used == numLiterals) {
      readValues(true);
    }
    return numLiterals - used;
  }

  private void skipCurrentLiterals(int valuesToSkip) {
    assert (used + valuesToSkip) <= numLiterals;
    used += valuesToSkip;
  }
  @Override
  public void nextVector(LongColumnVector previous, long previousLen) throws IOException {
    previous.isRepeating = true;
    for (int i = 0; i < previousLen; i++) {
      if (!previous.isNull[i]) {
        previous.vector[i] = next();
      } else {
        // The default value of null for int type in vectorized
        // processing is 1, so set that if the value is null
        previous.vector[i] = 1;
      }

      // The default value for nulls in Vectorization for int types is 1
      // and given that non null value can also be 1, we need to check for isNull also
      // when determining the isRepeating flag.
      if (previous.isRepeating
          && i > 0
          && (previous.vector[i - 1] != previous.vector[i] || previous.isNull[i - 1] != previous.isNull[i])) {
        previous.isRepeating = false;
      }
    }
  }

  @Override
  public void seek(PositionProvider index) throws IOException {
    input.seek(index);
    int consumed = (int) index.getNext();
    if (consumed != 0) {
      // a loop is required for cases where we break the run into two parts
      while (consumed > 0) {
        readValues(false);
        used = consumed;
        consumed -= numLiterals;
      }
    } else {
      used = 0;
      numLiterals = 0;
    }
  }

  @Override
  public void skip(long numValues) throws IOException {
    while (numValues > 0) {
      if (used == numLiterals) {
        readValues(false);
      }
      long consume = Math.min(numValues, numLiterals - used);
      used += consume;
      numValues -= consume;
    }
  }
}
