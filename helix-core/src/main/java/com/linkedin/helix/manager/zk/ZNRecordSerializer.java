/**
 * Copyright (C) 2012 LinkedIn Inc <opensource@linkedin.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.helix.manager.zk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.I0Itec.zkclient.serialize.ZkSerializer;
import org.apache.log4j.Logger;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;

import com.linkedin.helix.HelixException;
import com.linkedin.helix.ZNRecord;

public class ZNRecordSerializer implements ZkSerializer
{
  private static Logger logger = Logger.getLogger(ZNRecordSerializer.class);
  private ObjectMapper  mapper;

  public ZNRecordSerializer()
  {
    mapper = new ObjectMapper();
    SerializationConfig serializationConfig = mapper.getSerializationConfig();
    serializationConfig.set(SerializationConfig.Feature.INDENT_OUTPUT, false);
    serializationConfig.set(SerializationConfig.Feature.AUTO_DETECT_FIELDS, true);
    serializationConfig.set(SerializationConfig.Feature.CAN_OVERRIDE_ACCESS_MODIFIERS,
                            true);

    DeserializationConfig deserializationConfig = mapper.getDeserializationConfig();
    deserializationConfig.set(DeserializationConfig.Feature.AUTO_DETECT_FIELDS, true);
    deserializationConfig.set(DeserializationConfig.Feature.AUTO_DETECT_SETTERS, true);
    deserializationConfig.set(DeserializationConfig.Feature.FAIL_ON_UNKNOWN_PROPERTIES,
                              true);
  }

  private static int getListFieldBound(ZNRecord record)
  {
    int max = Integer.MAX_VALUE;
    if (record.getSimpleFields().containsKey(ZNRecord.LIST_FIELD_BOUND))
    {
      String maxStr = record.getSimpleField(ZNRecord.LIST_FIELD_BOUND);
      try
      {
        max = Integer.parseInt(maxStr);
      }
      catch (Exception e)
      {
        logger.error("IllegalNumberFormat for list field bound: " + maxStr);
      }
    }
    return max;
  }

  @Override
  public byte[] serialize(Object data)
  {
    if (!(data instanceof ZNRecord))
    {
      // null is NOT an instance of any class
      logger.error("Input object must be of type ZNRecord but it is " + data
          + ". Will not write to zk");
      throw new HelixException("Input object is not of type ZNRecord (was " + data + ")");
    }

    ZNRecord record = (ZNRecord) data;

    // apply retention policy
    int max = getListFieldBound(record);
    if (max < Integer.MAX_VALUE)
    {
      Map<String, List<String>> listMap = record.getListFields();
      for (String key : listMap.keySet())
      {
        List<String> list = listMap.get(key);
        if (list.size() > max)
        {
          listMap.put(key, list.subList(0, max));
        }
      }
    }

    StringWriter sw = new StringWriter();
    // byte[] compressBytes;
    try
    {
      mapper.writeValue(sw, data);
      // compressBytes = compressBytes(sw.toString());

    }
    catch (Exception e)
    {
      logger.error("Exception during data serialization. Will not write to zk. Data (first 1k): "
                       + sw.toString().substring(0, 1024),
                   e);
      throw new HelixException(e);
    }

    if (sw.toString().getBytes().length > ZNRecord.SIZE_LIMIT)
    {
      logger.error("Data size larger than 1M, ZNRecord.id: " + record.getId()
          + ". Will not write to zk. Data (first 1k): "
          + sw.toString().substring(0, 1024));
      throw new HelixException("Data size larger than 1M, ZNRecord.id: " + record.getId());
    }
    return sw.toString().getBytes();
    // return compressBytes;
  }

  @Override
  public Object deserialize(byte[] bytes)
  {
    if (bytes == null || bytes.length == 0)
    {
      logger.error("Znode is empty.");
      return null;
    }

    try
    {
//      ObjectMapper mapper = new ObjectMapper();
      // ByteArrayInputStream bais = new
      // ByteArrayInputStream(extractBytes(bytes).getBytes());
      ByteArrayInputStream bais = new ByteArrayInputStream(bytes);


      ZNRecord zn = mapper.readValue(bais, ZNRecord.class);
      return zn;
    }
    catch (Exception e)
    {
      logger.error("Exception during deserialization of bytes: " + new String(bytes), e);
      return null;
    }
  }

  public byte[] compressBytes(String data) throws Exception
  {
    // the format... data is the total string
    byte[] input = data.getBytes("UTF-8");
    // this function mainly generate the byte code
    Deflater df = new Deflater();
    // df.setLevel(Deflater.BEST_COMPRESSION);
    df.setInput(input);
    // we write the generated byte code in this array
    ByteArrayOutputStream baos = new ByteArrayOutputStream(input.length);
    df.finish();
    // segment segment pop....segment set 1024
    byte[] buff = new byte[1024];
    while (!df.finished())
    {
      // returns the generated code... index
      int count = df.deflate(buff);
      // write 4m 0 to count
      baos.write(buff, 0, count);
    }
    baos.close();
    byte[] output = baos.toByteArray();

    logger.info("Original: " + input.length);
    logger.info("Compressed: " + output.length);
    return output;
  }

  public String extractBytes(byte[] input) throws Exception
  {
    Inflater ifl = new Inflater(); // mainly generate the extraction
    // df.setLevel(Deflater.BEST_COMPRESSION);
    ifl.setInput(input);

    ByteArrayOutputStream baos = new ByteArrayOutputStream(input.length);
    byte[] buff = new byte[1024];
    while (!ifl.finished())
    {
      int count = ifl.inflate(buff);
      baos.write(buff, 0, count);
    }
    baos.close();
    byte[] output = baos.toByteArray();

    logger.info("Original: " + input.length);
    logger.info("Extracted: " + output.length);
    return new String(output);
  }
}
