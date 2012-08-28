package edu.washington.escience.myriad;

import java.nio.FloatBuffer;

import com.google.common.base.Preconditions;

public class FloatColumn extends Column {
  FloatBuffer data;

  public FloatColumn() {
    this.data = FloatBuffer.allocate(TupleBatch.BATCH_SIZE);
  }

  public float getFloat(int row) {
    Preconditions.checkElementIndex(row, data.position());
    return data.get(row);
  }

  public void putFloat(float value) {
    Preconditions.checkElementIndex(data.position(), data.capacity());
    data.put(value);
  }

  @Override
  public Object get(int row) {
    return Float.valueOf(getFloat(row));
  }

  @Override
  public void put(Object value) {
    putFloat((Float) value);
  }

  @Override
  int size() {
    return data.position();
  }
}