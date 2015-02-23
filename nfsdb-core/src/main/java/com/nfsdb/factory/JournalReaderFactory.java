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

package com.nfsdb.factory;

import com.nfsdb.Journal;
import com.nfsdb.JournalBulkReader;
import com.nfsdb.JournalKey;
import com.nfsdb.exceptions.JournalException;
import com.nfsdb.factory.configuration.JournalConfiguration;

import java.io.Closeable;

public interface JournalReaderFactory extends Closeable {

    <T> JournalBulkReader<T> bulkReader(Class<T> clazz, String location) throws JournalException;

    <T> JournalBulkReader<T> bulkReader(Class<T> clazz) throws JournalException;

    JournalBulkReader bulkReader(String location) throws JournalException;

    <T> JournalBulkReader<T> bulkReader(JournalKey<T> key) throws JournalException;

    void close();

    JournalExistenceCheck exists(String location);

    JournalConfiguration getConfiguration();

    <T> Journal<T> reader(JournalKey<T> key) throws JournalException;

    <T> Journal<T> reader(Class<T> clazz) throws JournalException;

    <T> Journal<T> reader(Class<T> clazz, String location) throws JournalException;

    Journal reader(String location) throws JournalException;

    <T> Journal<T> reader(Class<T> clazz, String location, int recordHint) throws JournalException;

    public static enum JournalExistenceCheck {
        EXISTS, DOES_NOT_EXIST, EXISTS_FOREIGN
    }

}
