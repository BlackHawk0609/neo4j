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
package org.neo4j.kernel.impl.api.index;

import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.neo4j.exceptions.UnderlyingStorageException;
import org.neo4j.internal.helpers.collection.Pair;
import org.neo4j.internal.helpers.collection.PrefetchingIterator;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.kernel.api.exceptions.index.IndexEntryConflictException;
import org.neo4j.kernel.api.index.IndexUpdater;
import org.neo4j.kernel.impl.store.MultipleUnderlyingStorageExceptions;

/**
 * Holds currently open index updaters that are created dynamically when requested for any existing index in
 * the given indexMap.
 *
 * Also provides a close method for closing and removing any remaining open index updaters.
 *
 * All updaters retrieved from this map must be either closed manually or handle duplicate calls to close
 * or must all be closed indirectly by calling close on this updater map.
 */
class IndexUpdaterMap implements AutoCloseable, Iterable<IndexUpdater>
{
    private final IndexUpdateMode indexUpdateMode;
    private final IndexMap indexMap;
    private final Map<IndexDescriptor,IndexUpdater> updaterMap;

    IndexUpdaterMap( IndexMap indexMap, IndexUpdateMode indexUpdateMode )
    {
        this.indexUpdateMode = indexUpdateMode;
        this.indexMap = indexMap;
        this.updaterMap = new HashMap<>();
    }

    IndexUpdater getUpdater( IndexDescriptor descriptor )
    {
        IndexUpdater updater = updaterMap.get( descriptor );
        if ( null == updater )
        {
            IndexProxy indexProxy = indexMap.getIndexProxy( descriptor );
            if ( null != indexProxy )
            {
                updater = indexProxy.newUpdater( indexUpdateMode );
                updaterMap.put( descriptor, updater );
            }
        }
        return updater;
    }

    private IndexUpdater getUpdater( IndexProxy proxy )
    {
        IndexUpdater updater = updaterMap.get( proxy.getDescriptor() );
        if ( null == updater )
        {
            updater = proxy.newUpdater( indexUpdateMode );
            updaterMap.put( proxy.getDescriptor(), updater );
        }
        return updater;
    }

    @Override
    public void close() throws UnderlyingStorageException
    {
        Set<Pair<IndexDescriptor,UnderlyingStorageException>> exceptions = null;

        for ( Map.Entry<IndexDescriptor,IndexUpdater> updaterEntry : updaterMap.entrySet() )
        {
            IndexUpdater updater = updaterEntry.getValue();
            try
            {
                updater.close();
            }
            catch ( UncheckedIOException | IndexEntryConflictException e )
            {
                if ( null == exceptions )
                {
                    exceptions = new HashSet<>();
                }
                exceptions.add( Pair.of( updaterEntry.getKey(), new UnderlyingStorageException( e ) ) );
            }
        }

        clear();

        if ( null != exceptions )
        {
            throw new MultipleUnderlyingStorageExceptions( exceptions );
        }
    }

    public void clear()
    {
        updaterMap.clear();
    }

    public boolean isEmpty()
    {
        return updaterMap.isEmpty();
    }

    public int size()
    {
        return updaterMap.size();
    }

    @Override
    public Iterator<IndexUpdater> iterator()
    {
        return new PrefetchingIterator<>()
        {
            private Iterator<IndexProxy> proxies = indexMap.getAllIndexProxies().iterator();

            @Override
            protected IndexUpdater fetchNextOrNull()
            {
                if ( proxies.hasNext() )
                {
                    return getUpdater( proxies.next() );
                }
                return null;
            }
        };
    }
}
