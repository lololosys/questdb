/*
 * Copyright (c) 2014-2015. Vlad Ilyushchenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nfsdb.imp.probes;

import com.nfsdb.column.ColumnType;
import com.nfsdb.factory.configuration.ColumnMetadata;
import com.nfsdb.imp.TypeProbe;

public class BooleanProbe implements TypeProbe {

    @Override
    public ColumnMetadata getMetadata() {
        ColumnMetadata m = new ColumnMetadata();
        m.type = ColumnType.BOOLEAN;
        m.size = 1;
        return m;
    }

    @Override
    public boolean probe(CharSequence seq) {
        return cmp(seq, "true") || cmp(seq, "false");
    }

    private boolean cmp(CharSequence l, CharSequence r) {
        int ll;
        if ((ll = l.length()) != r.length()) {
            return false;
        }

        for (int i = 0; i < ll; i++) {
            if (Character.toLowerCase(l.charAt(i)) != r.charAt(i)) {
                return false;
            }
        }

        return true;
    }
}
