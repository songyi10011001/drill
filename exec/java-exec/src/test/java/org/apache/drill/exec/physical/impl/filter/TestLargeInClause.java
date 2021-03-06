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
package org.apache.drill.exec.physical.impl.filter;

import org.apache.drill.BaseTestQuery;
import org.junit.Test;

public class TestLargeInClause extends BaseTestQuery {

  private static String getInIntList(int size){
    StringBuffer sb = new StringBuffer();
    for(int i =0; i < size; i++){
      if(i != 0){
        sb.append(", ");
      }
      sb.append(i);
    }
    return sb.toString();
  }

  private static String getInDateList(int size){
    StringBuffer sb = new StringBuffer();
    for(int i =0; i < size; i++){
      if(i != 0){
        sb.append(", ");
      }
      sb.append("DATE '1961-08-26'");
    }
    return sb.toString();
  }

  @Test
  public void queryWith300InConditions() throws Exception {
    test("select * from cp.`employee.json` where id in (" + getInIntList(300) + ")");
  }

  @Test
  public void queryWith50000InConditions() throws Exception {
    test("select * from cp.`employee.json` where id in (" + getInIntList(50000) + ")");
  }

  @Test
  public void queryWith50000DateInConditions() throws Exception {
    test("select * from cp.`employee.json` where cast(birth_date as date) in (" + getInDateList(500) + ")");
  }

}
