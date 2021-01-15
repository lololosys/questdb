/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin;

import io.questdb.StatelessGriffinTest;
import io.questdb.griffin.engine.functions.rnd.SharedRandom;
import io.questdb.std.Rnd;
import org.jetbrains.annotations.NotNull;
import org.junit.*;

import java.io.IOException;

import static io.questdb.griffin.CompiledQuery.ALTER;
import static io.questdb.griffin.CompiledQuery.REPLICATE;

public class ReplicationSqlTest extends AbstractGriffinTest {
    private static StatelessGriffinTest masterSetup = new StatelessGriffinTest();
    private static StatelessGriffinTest.StaticState masterState;

    @BeforeClass
    public static void startSlaveEngine() throws IOException {
        masterState = StatelessGriffinTest.setUpClass("dbMasterRoot");
    }

    @Before
    public void setUp0() {
        masterSetup.setUp(masterState);
    }

    @After
    public void cleanupSlaveEngine() {
        masterSetup.after(masterState);
    }

    @AfterClass
    public static void afterClass() {
        StatelessGriffinTest.afterClass(masterState);
    }


    @Before
    public void setUp3() {
        SharedRandom.RANDOM.set(new Rnd());
    }

    @Test
    public void testParseReplicationStart() throws Exception {
        assertMemoryLeak(
                () -> {
                    createX();
                    Assert.assertEquals(REPLICATE, runSlaveSql("replicate table \"temperature\" from \"LON\"").getType());
                }
        );
    }

    private void createX() throws SqlException {
        runMasterSql(
                "create table x as (" +
                        "select" +
                        " cast(x as int) i," +
                        " rnd_symbol('msft','ibm', 'googl') sym," +
                        " round(rnd_double(0)*100, 3) amt," +
                        " to_timestamp('2018-01', 'yyyy-MM') + x * 720000000 timestamp," +
                        " rnd_boolean() b," +
                        " rnd_str('ABC', 'CDE', null, 'XYZ') c," +
                        " rnd_double(2) d," +
                        " rnd_float(2) e," +
                        " rnd_short(10,1024) f," +
                        " rnd_date(to_date('2015', 'yyyy'), to_date('2016', 'yyyy'), 2) g," +
                        " rnd_symbol(4,4,4,2) ik," +
                        " rnd_long() j," +
                        " timestamp_sequence(0, 1000000000) k," +
                        " rnd_byte(2,50) l," +
                        " rnd_bin(10, 20, 2) m," +
                        " rnd_str(5,16,2) n" +
                        " from long_sequence(10)" +
                        ") timestamp (timestamp);"
        );
    }

    private @NotNull CompiledQuery runMasterSql(String sql) throws SqlException {
        return compiler.compile(sql, sqlExecutionContext);
    }

    private @NotNull CompiledQuery runSlaveSql(String sql) throws SqlException {
        return masterState.compiler.compile(sql, masterState.sqlExecutionContext);
    }
}
