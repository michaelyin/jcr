/*
 * Copyright (C) 2009 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.services.jcr.impl.core.query.ispn;

import org.exoplatform.container.configuration.ConfigurationManager;
import org.exoplatform.services.jcr.config.QueryHandlerEntry;
import org.exoplatform.services.jcr.config.RepositoryConfigurationException;
import org.exoplatform.services.jcr.impl.core.query.IndexerChangesFilter;
import org.exoplatform.services.jcr.impl.core.query.IndexerIoModeHandler;
import org.exoplatform.services.jcr.impl.core.query.IndexingTree;
import org.exoplatform.services.jcr.impl.core.query.QueryHandler;
import org.exoplatform.services.jcr.impl.core.query.SearchManager;
import org.exoplatform.services.jcr.impl.core.query.jbosscache.ChangesFilterListsWrapper;
import org.exoplatform.services.jcr.impl.core.query.lucene.ChangesHolder;
import org.exoplatform.services.jcr.infinispan.ISPNCacheFactory;
import org.exoplatform.services.jcr.infinispan.PrivilegedISPNCacheHelper;
import org.exoplatform.services.jcr.util.IdGenerator;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.infinispan.Cache;
import org.infinispan.CacheException;
import org.infinispan.loaders.CacheLoaderManager;

import java.io.IOException;
import java.io.Serializable;
import java.util.Set;

import javax.jcr.RepositoryException;

/**
 * This type of ChangeFilter offers an ability for each cluster instance to have own
 * local index (stack of indexes, from persistent to volatile). It uses ISPN cache for
 * Lucene Documents and UUIDs delivery. Each node works in ReadWrite mode, so manages 
 * it own volatile, merger, local list of persisted indexes and stand-alone 
 * UpdateInProgressMonitor implementation. 
 * This implementation is similar to ISPNIndexChangesFilter but does not use
 * ISPNIndexInfoss and ISPNIndexUpdateMonitor classes.
 *
 * @author <a href="mailto:anatoliy.bazko@gmail.com">Anatoliy Bazko</a>
 * @version $Id: LocalIndexChangesFilter.java 34360 2009-07-22 23:58:59Z tolusha $
 */
public class LocalIndexChangesFilter extends IndexerChangesFilter
{
   /**
    * Logger instance for this class
    */
   private final Log log = ExoLogger.getLogger("exo.jcr.component.core.LocalIndexChangesFilter");

   public static final String PARAM_INFINISPAN_CACHESTORE_CLASS = "infinispan-cachestore-classname";
   
   private final Cache<Serializable, Object> cache;

   private final int wsId;

   /**
    * LocalIndexChangesFilter constructor.
    */
   public LocalIndexChangesFilter(SearchManager searchManager, SearchManager parentSearchManager,
      QueryHandlerEntry config, IndexingTree indexingTree, IndexingTree parentIndexingTree, QueryHandler handler,
      QueryHandler parentHandler, ConfigurationManager cfm) throws IOException, RepositoryException,
      RepositoryConfigurationException
   {
      super(searchManager, parentSearchManager, config, indexingTree, parentIndexingTree, handler, parentHandler, cfm);

      this.wsId = searchManager.getWsId().hashCode();
      ISPNCacheFactory<Serializable, Object> factory = new ISPNCacheFactory<Serializable, Object>(cfm);
      config.putParameterValue(PARAM_INFINISPAN_CACHESTORE_CLASS, LocalIndexCacheStore.class.getName());
      this.cache = factory.createCache("Indexer-" + searchManager.getWsId(), config);

      CacheLoaderManager cacheLoaderManager =
         cache.getAdvancedCache().getComponentRegistry().getComponent(CacheLoaderManager.class);
      AbstractIndexerCacheStore cacheStore = (AbstractIndexerCacheStore)cacheLoaderManager.getCacheLoader();

      cacheStore.register(searchManager, parentSearchManager, handler, parentHandler);
      IndexerIoModeHandler modeHandler = cacheStore.getModeHandler();
      handler.setIndexerIoModeHandler(modeHandler);
      parentHandler.setIndexerIoModeHandler(modeHandler);

      // using default updateMonitor and default 
      if (!parentHandler.isInitialized())
      {
         parentHandler.init();
      }
      if (!handler.isInitialized())
      {
         handler.init();
      }
   }

   /**
    * {@inheritDoc}
    */
   @Override
   protected void doUpdateIndex(Set<String> removedNodes, Set<String> addedNodes, Set<String> parentRemovedNodes,
      Set<String> parentAddedNodes)
   {

      ChangesHolder changes = searchManager.getChanges(removedNodes, addedNodes);
      ChangesHolder parentChanges = parentSearchManager.getChanges(parentRemovedNodes, parentAddedNodes);

      if (changes == null && parentChanges == null)
      {
         return;
      }

      ChangesKey changesKey = new ChangesKey(wsId, IdGenerator.generate());
      try
      {
         PrivilegedISPNCacheHelper.put(cache, changesKey, new ChangesFilterListsWrapper(changes, parentChanges));
      }
      catch (CacheException e)
      {
         log.error(e.getLocalizedMessage(), e);
         logErrorChanges(handler, removedNodes, addedNodes);
         logErrorChanges(parentHandler, parentRemovedNodes, parentAddedNodes);
      }
   }

   /**
    * Log errors.
    * 
    * @param logHandler
    * @param removedNodes
    * @param addedNodes
    */
   private void logErrorChanges(QueryHandler logHandler, Set<String> removedNodes, Set<String> addedNodes)
   {
      try
      {
         logHandler.logErrorChanges(addedNodes, removedNodes);
      }
      catch (IOException ioe)
      {
         log.warn("Exception occure when errorLog writed. Error log is not complete. " + ioe, ioe);
      }
   }
}
