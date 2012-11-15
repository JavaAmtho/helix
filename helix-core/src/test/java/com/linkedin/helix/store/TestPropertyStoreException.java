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
package com.linkedin.helix.store;

import org.testng.annotations.Test;
import org.testng.AssertJUnit;

import com.linkedin.helix.store.PropertyStoreException;

public class TestPropertyStoreException
{
  @Test (groups = {"unitTest"})
  public void testPropertyStoreException()
  {
    PropertyStoreException exception = new PropertyStoreException("msg");
    AssertJUnit.assertEquals(exception.getMessage(), "msg");
    
    exception = new PropertyStoreException();
    AssertJUnit.assertNull(exception.getMessage());
  }

}
