/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.store;

import java.io.File;

import org.neo4j.configuration.Config;
import org.neo4j.internal.id.IdGeneratorFactory;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.impl.store.format.RecordFormats;
import org.neo4j.kernel.impl.store.record.PropertyKeyTokenRecord;
import org.neo4j.logging.LogProvider;

class PropertyKeyTokenStoreTest extends TokenStoreTestTemplate<PropertyKeyTokenRecord>
{
    @Override
    protected TokenStore<PropertyKeyTokenRecord> instantiateStore( File file, File idFile, IdGeneratorFactory generatorFactory, PageCache pageCache,
            LogProvider logProvider, DynamicStringStore nameStore, RecordFormats formats, Config config )
    {
        return new PropertyKeyTokenStore( file, idFile, config, generatorFactory, pageCache, logProvider, nameStore, formats );
    }
}
