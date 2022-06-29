/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
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

package io.questdb.std;

import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class OsUtilsTest {
    static final Log LOG = LogFactory.getLog(OsUtils.class);

    @Test
    public void testFileLimitLinux() {
        Assume.assumeTrue(Os.type == Os.LINUX_AMD64 || Os.type == Os.LINUX_ARM64);

        long fileLimit = FilesFacadeImpl.INSTANCE.getFileLimit();
        Assert.assertTrue(fileLimit > 0);

        System.out.println(fileLimit);
    }

    @Test
    public void testMapLimitLinux() {
        Assume.assumeTrue(Os.type == Os.LINUX_AMD64 || Os.type == Os.LINUX_ARM64);

        long mapCount = OsUtils.getMaxMapCount(LOG, FilesFacadeImpl.INSTANCE);
        Assert.assertTrue(mapCount > 0);

        System.out.println(mapCount);
    }
}
