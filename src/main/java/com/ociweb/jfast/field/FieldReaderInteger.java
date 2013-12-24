package com.ociweb.jfast.field;

import com.ociweb.jfast.primitive.PrimitiveReader;

public class FieldReaderInteger {

	private final int INSTANCE_MASK;
	private final PrimitiveReader reader;	
	private final int[]  lastValue;

	public FieldReaderInteger(PrimitiveReader reader, int[] values) {

		assert(values.length<TokenBuilder.MAX_INSTANCE);
		assert(isPowerOfTwo(values.length));
		
		this.INSTANCE_MASK = (values.length-1);
		this.reader = reader;
		this.lastValue = values;
	}
	
	static boolean isPowerOfTwo(int length) {
		
		while (0==(length&1)) {
			length = length>>1;
		}
		return length==1;
	}

	public void reset(DictionaryFactory df) {
		df.reset(lastValue);
	}

	public int readIntegerUnsigned(int token) {
		//no need to set initValueFlags for field that can never be null
		return lastValue[token & INSTANCE_MASK] = reader.readIntegerUnsigned();
	}

	public int readIntegerUnsignedOptional(int token, int valueOfOptional) {
		int value = reader.readIntegerUnsigned();
		if (0==value) {
			return valueOfOptional;
		} else {
			return --value;
		}
	}

	public int readIntegerUnsignedConstant(int token) {
		return (reader.popPMapBit()==0 ? 
					lastValue[token & INSTANCE_MASK]:
					reader.readIntegerUnsigned()	
				);
	}

	public int readIntegerUnsignedCopy(int token) {
		return (reader.popPMapBit()==0 ? 
				 lastValue[token & INSTANCE_MASK] : 
			     (lastValue[token & INSTANCE_MASK] = reader.readIntegerUnsigned()));
	}

	public int readIntegerUnsignedCopyOptional(int token, int valueOfOptional) {
		int value;
		if (reader.popPMapBit()==0) {
			value = lastValue[token & INSTANCE_MASK];
		} else {
			lastValue[token & INSTANCE_MASK] = value = reader.readIntegerUnsigned();
		}
		return (0 == value ? valueOfOptional: value-1);
	}
	
	
	public int readIntegerUnsignedDelta(int token) {
		//Delta opp never uses PMAP
		int index = token & INSTANCE_MASK;
		return (lastValue[index] = (lastValue[index]+reader.readIntegerSigned()));
		
	}
	
	public int readIntegerUnsignedDeltaOptional(int token, int valueOfOptional) {
		//Delta opp never uses PMAP
		long value = reader.readLongSigned();
		if (0==value) {
			lastValue[token & INSTANCE_MASK]=0;
			return valueOfOptional;
		} else {
			return lastValue[token & INSTANCE_MASK] += (value>0 ? value-1 : value);
			
		}
	}

	public int readIntegerUnsignedDefault(int token) {
		if (reader.popPMapBit()==0) {
			//default value 
			return lastValue[token & INSTANCE_MASK];
		} else {
			//override value, but do not replace the default
			return reader.readIntegerUnsigned();
		}
	}

	public int readIntegerUnsignedDefaultOptional(int token, int valueOfOptional) {
		if (reader.popPMapBit()==0) {
			
			int idx = token & INSTANCE_MASK;
			if (lastValue[idx] == 0) {
				//default value is null so return optional.
				return valueOfOptional;
			} else {
				//default value 
				return lastValue[idx];
			}
			
		} else {
			int value = reader.readIntegerUnsigned();
			if (value==0) {
				return valueOfOptional;
			} else {
				return value-1;
			}
		}
	}

	public int readIntegerUnsignedIncrement(int token) {
		
		if (reader.popPMapBit()==0) {
			//increment old value
			return ++lastValue[token & INSTANCE_MASK];
		} else {
			//assign and return new value
			return lastValue[token & INSTANCE_MASK] = reader.readIntegerUnsigned();
		}
	}


	public int readIntegerUnsignedIncrementOptional(int token, int valueOfOptional) {
		int instance = token & INSTANCE_MASK;
		
		if (reader.popPMapBit()==0) {
			return (lastValue[instance] == 0 ? valueOfOptional: ++lastValue[instance]);
		} else {
			int value = reader.readIntegerUnsigned();
			if (value==0) {
				lastValue[instance] = 0;
				return valueOfOptional;
			} else {
				return (lastValue[instance] = value)-1;
			}
		}
	}

	//////////////
	//////////////
	//////////////
	
	public int readIntegerSigned(int token) {
		//no need to set initValueFlags for field that can never be null
		return lastValue[token & INSTANCE_MASK] = reader.readIntegerSigned();
	}

	public int readIntegerSignedOptional(int token, int valueOfOptional) {
		int value = reader.readIntegerSigned();
		lastValue[token & INSTANCE_MASK] = value;//needed for dynamic read behavior.
		if (0==value) {
			return valueOfOptional;
		} else {
			return value-1;
		}
	}

	public int readIntegerSignedConstant(int token) {
		return (reader.popPMapBit()==0 ? 
					lastValue[token & INSTANCE_MASK]:
					reader.readIntegerSigned()	
				);
	}

	public int readIntegerSignedCopy(int token) {
		return (reader.popPMapBit()==0 ? 
				 lastValue[token & INSTANCE_MASK] : 
			     (lastValue[token & INSTANCE_MASK] = reader.readIntegerSigned()));
	}

	public int readIntegerSignedCopyOptional(int token, int valueOfOptional) {
		//if zero then use old value.
		int value;
		if (reader.popPMapBit()==0) {
			value = lastValue[token & INSTANCE_MASK];
		} else {
			lastValue[token & INSTANCE_MASK] = value = reader.readIntegerSigned();
		}
		return (0 == value ? valueOfOptional: (value>0 ? value-1 : value));
	}
	
	
	public int readIntegerSignedDelta(int token) {
		//Delta opp never uses PMAP
		int index = token & INSTANCE_MASK;
		return (lastValue[index] = (lastValue[index]+reader.readIntegerSigned()));
		
	}
	
	public int readIntegerSignedDeltaOptional(int token, int valueOfOptional) {
		//Delta opp never uses PMAP
		long value = reader.readLongSigned();
		if (0==value) {
			lastValue[token & INSTANCE_MASK]=0;
			return valueOfOptional;
		} else {
			return lastValue[token & INSTANCE_MASK] += (value>0?value-1:value);
		}
	}

	public int readIntegerSignedDefault(int token) {
		if (reader.popPMapBit()==0) {
			//default value 
			return lastValue[token & INSTANCE_MASK];
		} else {
			//override value, but do not replace the default
			return reader.readIntegerSigned();
		}
	}

	public int readIntegerSignedDefaultOptional(int token, int valueOfOptional) {
		if (reader.popPMapBit()==0) {
			
			int idx = token & INSTANCE_MASK;
			if (lastValue[idx] == 0) {
				//default value is null so return optional.
				return valueOfOptional;
			} else {
				//default value 
				return lastValue[idx];
			}
			
		} else {
			int value = reader.readIntegerSigned();
			if (value==0) {
				return valueOfOptional;
			} else {
				return --value;
			}
		}
	}

	public int readIntegerSignedIncrement(int token) {
		if (reader.popPMapBit()==0) {
			//increment old value
			return ++lastValue[token & INSTANCE_MASK];
		} else {
			//assign and return new value
			return lastValue[token & INSTANCE_MASK] = reader.readIntegerSigned();
		}
	}


	public int readIntegerSignedIncrementOptional(int token, int valueOfOptional) {
		int instance = token & INSTANCE_MASK;
		
		if (reader.popPMapBit()==0) {
			return (lastValue[instance] == 0 ? valueOfOptional: ++lastValue[instance]);
		} else {
			int value = reader.readIntegerSigned();
			if (value==0) {
				lastValue[instance] = 0;
				return valueOfOptional;
			} else {
				return value>0 ? (lastValue[instance] = value)-1 : (lastValue[instance] = value);
			}
		}
		
	}
	
	
	
	
}