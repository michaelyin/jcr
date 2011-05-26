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
package org.exoplatform.services.jcr.impl.dataflow.persistent;

import org.exoplatform.commons.utils.SecurityHelper;
import org.exoplatform.services.jcr.dataflow.ItemStateChangesLog;
import org.exoplatform.services.jcr.dataflow.persistent.MandatoryItemsPersistenceListener;
import org.exoplatform.services.jcr.dataflow.persistent.WorkspaceStorageCache;
import org.exoplatform.services.jcr.datamodel.ItemData;
import org.exoplatform.services.jcr.datamodel.ItemType;
import org.exoplatform.services.jcr.datamodel.NodeData;
import org.exoplatform.services.jcr.datamodel.NullItemData;
import org.exoplatform.services.jcr.datamodel.NullNodeData;
import org.exoplatform.services.jcr.datamodel.NullPropertyData;
import org.exoplatform.services.jcr.datamodel.PropertyData;
import org.exoplatform.services.jcr.datamodel.QPathEntry;
import org.exoplatform.services.jcr.datamodel.ValueData;
import org.exoplatform.services.jcr.impl.Constants;
import org.exoplatform.services.jcr.impl.backup.ResumeException;
import org.exoplatform.services.jcr.impl.backup.SuspendException;
import org.exoplatform.services.jcr.impl.backup.Suspendable;
import org.exoplatform.services.jcr.impl.core.itemfilters.QPathEntryFilter;
import org.exoplatform.services.jcr.impl.dataflow.session.TransactionableResourceManager;
import org.exoplatform.services.jcr.impl.dataflow.session.TransactionableResourceManagerListener;
import org.exoplatform.services.jcr.impl.storage.SystemDataContainerHolder;
import org.exoplatform.services.jcr.impl.storage.jdbc.JDBCStorageConnection;
import org.exoplatform.services.jcr.storage.WorkspaceDataContainer;
import org.exoplatform.services.rpc.RPCException;
import org.exoplatform.services.rpc.RPCService;
import org.exoplatform.services.rpc.RemoteCommand;
import org.exoplatform.services.rpc.TopologyChangeEvent;
import org.exoplatform.services.rpc.TopologyChangeListener;
import org.exoplatform.services.transaction.TransactionService;

import java.io.Serializable;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jcr.RepositoryException;
import javax.transaction.Status;
import javax.transaction.TransactionManager;

/**
 * Created by The eXo Platform SAS. 
 * 
 * <br/>
 * Author : Peter Nedonosko peter.nedonosko@exoplatform.com.ua
 * 13.04.2006
 * 
 * @version $Id$
 */
public class CacheableWorkspaceDataManager extends WorkspacePersistentDataManager implements Suspendable,
   TopologyChangeListener
{

   /**
    * Items cache.
    */
   protected final WorkspaceStorageCache cache;

   /**
    * Requests cache.
    */
   protected final ConcurrentMap<Integer, DataRequest> requestCache;

   /**
    * The resource manager
    */
   private final TransactionableResourceManager txResourceManager;
   
   private TransactionManager transactionManager;

   /**
    * The service for executing commands on all nodes of cluster.
    */
   protected final RPCService rpcService;

   /**
    * The amount of current working threads.
    */
   protected AtomicInteger workingThreads = new AtomicInteger();

   /**
    * Indicates if component suspended or not.
    */
   protected boolean isSuspended = false;

   /**
    * Allows to make all threads waiting until resume. 
    */
   protected CountDownLatch latcher = null;

   /**
    * Indicates that node keep responsible for resuming.
    */
   protected Boolean isResponsibleForResuming = false;

   /**
    * Request to all nodes to check if there is someone who responsible for resuming.
    */
   private RemoteCommand requestForResponsibleForResuming;

   /**
    * Suspend remote command.
    */
   private RemoteCommand suspend;

   /**
    * Resume remote command.
    */
   private RemoteCommand resume;

   /**
    * ItemData request, used on get operations.
    * 
    */
   protected class DataRequest
   {
      /**
       * GET_NODES type.
       */
      static public final int GET_NODES = 1;

      /**
       * GET_PROPERTIES type.
       */
      static public final int GET_PROPERTIES = 2;

      /**
       * GET_ITEM_ID type.
       */
      static private final int GET_ITEM_ID = 3;

      /**
       * GET_ITEM_NAME type.
       */
      static private final int GET_ITEM_NAME = 4;

      /**
       * GET_LIST_PROPERTIES type.
       */
      static private final int GET_LIST_PROPERTIES = 5;

      /**
       * GET_REFERENCES type.
       */
      static public final int GET_REFERENCES = 6;

      /**
       * Request type.
       */
      protected final int type;

      /**
       * Item parentId.
       */
      protected final String parentId;

      /**
       * Item id.
       */
      protected final String id;

      /**
       * Item name.
       */
      protected final QPathEntry name;

      /**
       * Hash code.
       */
      protected final int hcode;

      /**
       * Readiness latch.
       */
      protected CountDownLatch ready = new CountDownLatch(1);

      /**
       * DataRequest constructor.
       * 
       * @param parentId
       *          parent id
       * @param type
       *          request type
       */
      DataRequest(String parentId, int type)
      {
         this.parentId = parentId;
         this.name = null;
         this.id = null;
         this.type = type;

         // hashcode
         this.hcode = 31 * (31 + this.type) + this.parentId.hashCode();
      }

      /**
       * DataRequest constructor.
       * 
       * @param parentId
       *          parent id
       * @param name
       *          Item name
       */
      DataRequest(String parentId, QPathEntry name)
      {
         this.parentId = parentId;
         this.name = name;
         this.id = null;
         this.type = GET_ITEM_NAME;

         // hashcode
         int hc = 31 * (31 + this.type) + this.parentId.hashCode();
         this.hcode = 31 * hc + this.name.hashCode();
      }

      /**
       * DataRequest constructor.
       * 
       * @param id
       *          Item id
       */
      DataRequest(String id)
      {
         this.parentId = null;
         this.name = null;
         this.id = id;
         this.type = GET_ITEM_ID;

         // hashcode
         this.hcode = 31 * (31 + this.type) + (this.id == null ? 0 : this.id.hashCode());
      }

      /**
       * Start the request, each same will wait till this will be finished
       */
      void start()
      {
         DataRequest request = requestCache.putIfAbsent(this.hashCode(), this);
         if (request != null)
         {
            request.await();
         }
      }

      /**
       * Done the request. Must be called after the data request will be finished. This call allow
       * another same requests to be performed.
       */
      void done()
      {
         this.ready.countDown();
         requestCache.remove(this.hashCode(), this);
      }

      /**
       * Await this thread for another one running same request.
       * 
       */
      void await()
      {
         try
         {
            this.ready.await();
         }
         catch (InterruptedException e)
         {
            LOG.warn("Can't wait for same request process. " + e, e);
         }
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public boolean equals(Object obj)
      {
         return this.hcode == obj.hashCode();
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public int hashCode()
      {
         return hcode;
      }
   }

   /**
   * This class is a decorator on the top of the {@link WorkspaceStorageCache} to manage the case
   * where the cache is disabled at the beginning then potentially enabled later
   */
   private class CacheItemsPersistenceListener implements MandatoryItemsPersistenceListener
   {
      /**
       * {@inheritDoc}
      */
      public boolean isTXAware()
      {
         return cache.isTXAware();
      }

      /**
       * {@inheritDoc}
       */
      public void onSaveItems(ItemStateChangesLog itemStates)
      {
         if (cache.isEnabled())
         {
            cache.onSaveItems(itemStates);
         }
      }
   }

   /**
    * CacheableWorkspaceDataManager constructor.
    * 
    * @param dataContainer
    *          Workspace data container (persistent level)
    * @param cache
    *          Items cache
    * @param systemDataContainerHolder
    *          System Workspace data container (persistent level)
    * @param txResourceManager
    *          the resource manager used to manage the whole tx
    * @param transactionService 
    *          TransactionService  
    * @param rpcService
    *          the service for executing commands on all nodes of cluster
    */
   public CacheableWorkspaceDataManager(WorkspaceDataContainer dataContainer, WorkspaceStorageCache cache,
      SystemDataContainerHolder systemDataContainerHolder, TransactionableResourceManager txResourceManager, 
      TransactionService transactionService, RPCService rpcService)
   {
      super(dataContainer, systemDataContainerHolder, txResourceManager);
      this.cache = cache;

      this.requestCache = new ConcurrentHashMap<Integer, DataRequest>();
      addItemPersistenceListener(new CacheItemsPersistenceListener());

      transactionManager = transactionService.getTransactionManager();

      this.rpcService = rpcService;
      this.txResourceManager = txResourceManager;
      doInitRemoteCommands();
   }

   /**
    * CacheableWorkspaceDataManager constructor.
    * 
    * @param dataContainer
    *          Workspace data container (persistent level)
    * @param cache
    *          Items cache
    * @param systemDataContainerHolder
    *          System Workspace data container (persistent level)
    * @param txResourceManager
    *          the resource manager used to manage the whole tx
    * @param transactionService TransactionService         
    */
   public CacheableWorkspaceDataManager(WorkspaceDataContainer dataContainer, WorkspaceStorageCache cache,
      SystemDataContainerHolder systemDataContainerHolder, TransactionableResourceManager txResourceManager,
      TransactionService transactionService)
   {
      this(dataContainer, cache, systemDataContainerHolder, txResourceManager, transactionService, null);
   }

   /**
    * CacheableWorkspaceDataManager constructor.
    * 
    * @param dataContainer
    *          Workspace data container (persistent level)
    * @param cache
    *          Items cache
    * @param systemDataContainerHolder
    *          System Workspace data container (persistent level)
    * @param txResourceManager
    *          the resource manager used to manage the whole tx
    */
   public CacheableWorkspaceDataManager(WorkspaceDataContainer dataContainer, WorkspaceStorageCache cache,
      SystemDataContainerHolder systemDataContainerHolder, TransactionableResourceManager txResourceManager,
      RPCService rpcService)
   {
      super(dataContainer, systemDataContainerHolder, txResourceManager);
      this.cache = cache;

      this.requestCache = new ConcurrentHashMap<Integer, DataRequest>();
      addItemPersistenceListener(new CacheItemsPersistenceListener());

      try
      {
         transactionManager = (TransactionManager)cache.getClass().getMethod("getTransactionManager", null).invoke(null, null);
      }
      catch (Exception e)
      {
         LOG.debug("Could not get the transaction manager from the cache", e);
         transactionManager = null;
      }

      this.rpcService = rpcService;
      this.txResourceManager = txResourceManager;      
      doInitRemoteCommands();
   }

   /**
    * CacheableWorkspaceDataManager constructor.
    * 
    * @param dataContainer
    *          Workspace data container (persistent level)
    * @param cache
    *          Items cache
    * @param systemDataContainerHolder
    *          System Workspace data container (persistent level)
    * @param txResourceManager
    *          the resource manager used to manage the whole tx
    */
   public CacheableWorkspaceDataManager(WorkspaceDataContainer dataContainer, WorkspaceStorageCache cache,
      SystemDataContainerHolder systemDataContainerHolder, TransactionableResourceManager txResourceManager)
   {
      this(dataContainer, cache, systemDataContainerHolder, txResourceManager, (RPCService)null);
   }

   /**
    * CacheableWorkspaceDataManager constructor.
    * 
    * @param dataContainer
    *          Workspace data container (persistent level)
    * @param cache
    *          Items cache
    * @param systemDataContainerHolder
    *          System Workspace data container (persistent level)
    */
   protected CacheableWorkspaceDataManager(WorkspaceDataContainer dataContainer, WorkspaceStorageCache cache,
      SystemDataContainerHolder systemDataContainerHolder)
   {
      this(dataContainer, cache, systemDataContainerHolder, null, (RPCService)null);
   }

   /**
    * Get Items Cache.
    * 
    * @return WorkspaceStorageCache
    */
   public WorkspaceStorageCache getCache()
   {
      return cache;
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public int getChildNodesCount(NodeData parent) throws RepositoryException
   {
      if (cache.isEnabled())
      {
         int childCount = cache.getChildNodesCount(parent);
         if (childCount >= 0)
         {
            return childCount;
         }
      }

      return super.getChildNodesCount(parent);
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public List<NodeData> getChildNodesData(NodeData nodeData) throws RepositoryException
   {
      return getChildNodesData(nodeData, false);
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public List<NodeData> getChildNodesData(NodeData parentData, List<QPathEntryFilter> patternFilters)
      throws RepositoryException
   {
      return getChildNodesDataByPattern(parentData, patternFilters);
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public List<PropertyData> getChildPropertiesData(NodeData nodeData) throws RepositoryException
   {
      List<PropertyData> childs = getChildPropertiesData(nodeData, false);
      for (PropertyData prop : childs)
      {
         fixPropertyValues(prop);
      }

      return childs;
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public List<PropertyData> getChildPropertiesData(NodeData nodeData, List<QPathEntryFilter> itemDataFilters)
      throws RepositoryException
   {
      List<PropertyData> childs = getChildPropertiesDataByPattern(nodeData, itemDataFilters);
      for (PropertyData prop : childs)
      {
         fixPropertyValues(prop);
      }

      return childs;
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public ItemData getItemData(NodeData parentData, QPathEntry name) throws RepositoryException
   {
      return getItemData(parentData, name, ItemType.UNKNOWN);
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public ItemData getItemData(NodeData parentData, QPathEntry name, ItemType itemType) throws RepositoryException
   {
      if (cache.isEnabled())
      {
         // 1. Try from cache
         ItemData data = getCachedItemData(parentData, name, itemType);

         // 2. Try from container
         if (data == null)
         {
            final DataRequest request = new DataRequest(parentData.getIdentifier(), name);

            try
            {
               request.start();
               // Try first to get the value from the cache since a
               // request could have been launched just before
               data = getCachedItemData(parentData, name, itemType);
               if (data == null)
               {
                  data = getPersistedItemData(parentData, name, itemType);
               }
            }
            finally
            {
               request.done();
            }
         }

         if (data instanceof NullItemData)
         {
            return null;
         }

         if (data != null && !data.isNode())
         {
            fixPropertyValues((PropertyData)data);
         }

         return data;
      }
      else
      {
         return super.getItemData(parentData, name, itemType);
      }
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public ItemData getItemData(String identifier) throws RepositoryException
   {
      if (cache.isEnabled())
      {
         // 1. Try from cache
         ItemData data = getCachedItemData(identifier);

         // 2 Try from container
         if (data == null)
         {
            final DataRequest request = new DataRequest(identifier);

            try
            {
               request.start();
               // Try first to get the value from the cache since a
               // request could have been launched just before
               data = getCachedItemData(identifier);
               if (data == null)
               {
                  data = getPersistedItemData(identifier);
               }
            }
            finally
            {
               request.done();
            }
         }

         if (data instanceof NullItemData)
         {
            return null;
         }

         if (data != null && !data.isNode())
         {
            fixPropertyValues((PropertyData)data);
         }

         return data;
      }
      else
      {
         return super.getItemData(identifier);
      }
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public List<PropertyData> getReferencesData(String identifier, boolean skipVersionStorage)
      throws RepositoryException
   {
      List<PropertyData> props = getReferencedPropertiesData(identifier);

      if (skipVersionStorage)
      {
         List<PropertyData> result = new ArrayList<PropertyData>();
         for (int i = 0, length = props.size(); i < length; i++)
         {
            PropertyData prop = props.get(i);
            if (!prop.getQPath().isDescendantOf(Constants.JCR_VERSION_STORAGE_PATH))
            {
               result.add(prop);
            }
         }

         return result;
      }

      return props;
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public List<PropertyData> listChildPropertiesData(NodeData nodeData) throws RepositoryException
   {
      return listChildPropertiesData(nodeData, false);
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public void save(final ItemStateChangesLog changesLog) throws RepositoryException
   {
      if (isSuspended)
      {
         try
         {
            latcher.await();
         }
         catch (InterruptedException e)
         {
            throw new RepositoryException(e);
         }
      }

      workingThreads.incrementAndGet();
      try
      {
         SecurityHelper.doPrivilegedExceptionAction(new PrivilegedExceptionAction<Void>()
         {
            public Void run() throws Exception
            {
               doSave(changesLog);
               return null;
            }
         });      
      }
      catch (PrivilegedActionException e)
      {
         Throwable cause = e.getCause();
         if (cause instanceof RepositoryException)
         {
            throw (RepositoryException)cause;
         }
         else if (cause instanceof RuntimeException)
         {
            throw (RuntimeException)cause;
         }
         else
         {
            throw new RuntimeException(cause);
         }
      }
      finally
      {
         workingThreads.decrementAndGet();
      }
   }

   private void doSave(final ItemStateChangesLog changesLog) throws RepositoryException
   {
      ChangesLogWrapper logWrapper = new ChangesLogWrapper(changesLog);

      if (isTxAware())
      {
         if (txResourceManager != null && txResourceManager.isGlobalTxActive())
         {
            super.save(logWrapper);
            registerListener(logWrapper);
         }
         else
         {
            doBegin();
            try
            {
               super.save(logWrapper);
            }
            catch (Exception e)
            {
               doRollback();
               if (e instanceof RepositoryException)
               {
                  throw (RepositoryException)e;
               }
               else
               {
                  throw new RepositoryException("Could not save the changes", e);
               }
            }
            doCommit();
            // notify listeners after storage commit
            notifySaveItems(logWrapper.getChangesLog(), false);
         }
      }
      else
      {
         // save normally 
         super.save(logWrapper);

         // notify listeners after storage commit
         notifySaveItems(logWrapper.getChangesLog(), false);
      }      
   }
   
   /**
    * Commits the tx
    * @throws RepositoryException if the tx could not be committed.
    */
   private void doCommit() throws RepositoryException
   {
      try
      {
         transactionManager.commit();
      }
      catch (Exception e)
      {
         throw new RepositoryException("Could not commit the changes", e);
      }
   }

   /**
    * Starts a new Tx
    * @throws RepositoryException if the tx could not be created
    */
   private void doBegin() throws RepositoryException
   {
      try
      {
         transactionManager.begin();
      }
      catch (Exception e)
      {
         throw new RepositoryException("Could not create a new Tx", e);
      }
   }
   
   /**
    * Performs rollback of the action.
    */
   private void doRollback()
   {
      try
      {
         transactionManager.rollback();
      }
      catch (Exception e)
      {
         LOG.error("Rollback error ", e);
      }
   }

   /**
    * This will allow to notify listeners that are not TxAware once the Tx is committed
    * @param logWrapper
    * @throws RepositoryException if any error occurs
    */
   private void registerListener(final ChangesLogWrapper logWrapper) throws RepositoryException
   {
      try
      {
         // Why calling the listeners non tx aware has been done like this:
         // 1. If we call them in the commit phase and we use Arjuna with ISPN, we get:
         //       ActionStatus.COMMITTING > is not in a valid state to be invoking cache operations on.
         //       at org.infinispan.interceptors.TxInterceptor.enlist(TxInterceptor.java:195)
         //       at org.infinispan.interceptors.TxInterceptor.enlistReadAndInvokeNext(TxInterceptor.java:167)
         //       at org.infinispan.interceptors.TxInterceptor.visitGetKeyValueCommand(TxInterceptor.java:162)
         //       at org.infinispan.commands.read.GetKeyValueCommand.acceptVisitor(GetKeyValueCommand.java:64)
         //    This is due to the fact that ISPN enlist the cache even for a read access and enlistments are not 
         //    allowed in the commit phase
         // 2. If we call them in the commit phase, we use Arjuna with ISPN and we suspend the current tx, we get deadlocks because we 
         //    try to acquire locks on cache entries that have been locked by the main tx.
         // 3. If we call them in the afterComplete, we use JOTM with ISPN and we suspend and resume the current tx, we get:
         //       jotm: resume: Invalid Transaction Status:STATUS_COMMITTED (Current.java, line 743) 
         //       javax.transaction.InvalidTransactionException: Invalid resume org.objectweb.jotm.TransactionImpl
         //       at org.objectweb.jotm.Current.resume(Current.java:744)
         //    This is due to the fact that it is not allowed to resume a tx when its status is STATUS_COMMITED

         txResourceManager.addListener(new TransactionableResourceManagerListener()
         {
            public void onCommit(boolean onePhase) throws Exception
            {
            }

            public void onAfterCompletion(int status) throws Exception
            {
               if (status == Status.STATUS_COMMITTED)
               {
                  // Since the tx is successfully committed we can call components non tx aware
                  
                  // The listeners will need to be executed outside the current tx so we suspend
                  // the current tx we can face enlistment issues on product like ISPN
                  transactionManager.suspend();
                  notifySaveItems(logWrapper.getChangesLog(), false);
                  // Since the resume method could cause issue with some TM at this stage, we don't resume the tx
               }
            }

            public void onAbort() throws Exception
            {
            }
         });
      }
      catch (Exception e)
      {
         throw new RepositoryException("The listener for the components not tx aware could not be added", e);
      }
   }

   /**
    * Get cached ItemData.
    * 
    * @param parentData
    *          parent
    * @param name
    *          Item name
    * @param itemType
    *          item type          
    * @return ItemData
    * @throws RepositoryException
    *           error
    */
   protected ItemData getCachedItemData(NodeData parentData, QPathEntry name, ItemType itemType)
      throws RepositoryException
   {
      return cache.isEnabled() ? cache.get(parentData.getIdentifier(), name, itemType) : null;
   }

   /**
    * Returns an item from cache by Identifier or null if the item don't cached.
    * 
    * @param identifier
    *          Item id
    * @return ItemData
    * @throws RepositoryException
    *           error
    */
   protected ItemData getCachedItemData(String identifier) throws RepositoryException
   {
      return cache.isEnabled() ? cache.get(identifier) : null;
   }

   /**
    * Get child NodesData.
    * 
    * @param nodeData
    *          parent
    * @param forcePersistentRead
    *          true if persistent read is required (without cache)
    * @return List<NodeData>
    * @throws RepositoryException
    *           Repository error
    */
   protected List<NodeData> getChildNodesData(NodeData nodeData, boolean forcePersistentRead)
      throws RepositoryException
   {

      List<NodeData> childNodes = null;
      if (!forcePersistentRead && cache.isEnabled())
      {
         childNodes = cache.getChildNodes(nodeData);
         if (childNodes != null)
         {
            return childNodes;
         }
      }
      final DataRequest request = new DataRequest(nodeData.getIdentifier(), DataRequest.GET_NODES);

      try
      {
         request.start();
         if (!forcePersistentRead && cache.isEnabled())
         {
            // Try first to get the value from the cache since a
            // request could have been launched just before
            childNodes = cache.getChildNodes(nodeData);
            if (childNodes != null)
            {
               return childNodes;
            }
         }
         childNodes = super.getChildNodesData(nodeData);
         if (cache.isEnabled())
         {
            NodeData parentData = (NodeData)getItemData(nodeData.getIdentifier());

            if (parentData != null)
            {
               cache.addChildNodes(parentData, childNodes);
            }
         }
         return childNodes;
      }
      finally
      {
         request.done();
      }
   }

   protected List<NodeData> getChildNodesDataByPattern(NodeData parentData, List<QPathEntryFilter> patternFilters)
      throws RepositoryException
   {
      if (!cache.isEnabled())
      {
         return super.getChildNodesData(parentData, patternFilters);
      }

      if (!cache.isPatternSupported())
      {
         return getChildNodesData(parentData);
      }

      // 1. check cache - outside data request

      List<NodeData> childNodesList = cache.getChildNodes(parentData);
      if (childNodesList != null)
      {
         return childNodesList;
      }

      Map<String, NodeData> childNodesMap = new HashMap<String, NodeData>();

      Set<QPathEntryFilter> uncachedPatterns = new HashSet<QPathEntryFilter>();
      for (int i = 0; i < patternFilters.size(); i++)
      {
         if (patternFilters.get(i).isExactName())
         {
            ItemData data = getCachedItemData(parentData, patternFilters.get(i).getQPathEntry(), ItemType.NODE);
            if (data != null)
            {
               if (!(data instanceof NullItemData))
               {
                  childNodesMap.put(data.getIdentifier(), (NodeData)data);
               }
            }
            else
            {
               uncachedPatterns.add(patternFilters.get(i));
            }
         }
         else
         {
            // get nodes list by pattern
            List<NodeData> cachedItemList = cache.getChildNodes(parentData, patternFilters.get(i));
            if (cachedItemList != null)
            {
               //merge results
               for (int j = 0, length = cachedItemList.size(); j < length; j++)
               {
                  childNodesMap.put(cachedItemList.get(j).getIdentifier(), cachedItemList.get(j));
               }
            }
            else
            {
               uncachedPatterns.add(patternFilters.get(i));
            }
         }
      }

      // 2. check cache - inside data requests
      if (!uncachedPatterns.isEmpty())
      {
         List<DataRequest> requests = new ArrayList<DataRequest>();
         try
         {
            final DataRequest request = new DataRequest(parentData.getIdentifier(), DataRequest.GET_NODES);
            request.start();
            requests.add(request);
            // Try first to get the value from the cache since a
            // request could have been launched just before
            childNodesList = cache.getChildNodes(parentData);
            if (childNodesList != null)
            {
               return childNodesList;
            }

            Iterator<QPathEntryFilter> patternIterator = uncachedPatterns.iterator();
            while (patternIterator.hasNext())
            {
               QPathEntryFilter pattern = patternIterator.next();
               if (pattern.isExactName())
               {
                  DataRequest exactNameRequest = new DataRequest(parentData.getIdentifier(), pattern.getQPathEntry());
                  exactNameRequest.start();
                  requests.add(exactNameRequest);

                  ItemData data = getCachedItemData(parentData, pattern.getQPathEntry(), ItemType.NODE);
                  if (data != null)
                  {
                     if (!(data instanceof NullItemData))
                     {
                        childNodesMap.put(data.getIdentifier(), (NodeData)data);
                     }
                     patternIterator.remove();
                  }
               }
               else
               {
                  // get node list by pattern
                  List<NodeData> cachedItemList = cache.getChildNodes(parentData, pattern);
                  if (cachedItemList != null)
                  {
                     //merge results
                     for (int j = 0, length = cachedItemList.size(); j < length; j++)
                     {
                        childNodesMap.put(cachedItemList.get(j).getIdentifier(), cachedItemList.get(j));
                     }
                     patternIterator.remove();
                  }
               }
            }
            patternIterator = null;

            // execute all patterns and put result in cache
            if (!uncachedPatterns.isEmpty())
            {
               List<NodeData> persistedItemList =
                  super.getChildNodesData(parentData, new ArrayList<QPathEntryFilter>(uncachedPatterns));

               if (persistedItemList.size() > 0)
               {
                  NodeData parent = (NodeData)getItemData(parentData.getIdentifier());
                  if (parent != null)
                  {
                     // filter nodes list for each exact name
                     patternIterator = uncachedPatterns.iterator();
                     while (patternIterator.hasNext())
                     {
                        QPathEntryFilter pattern = patternIterator.next();
                        List<NodeData> persistedNodeData = (List<NodeData>)pattern.accept(persistedItemList);
                        if (pattern.isExactName())
                        {
                           if (persistedNodeData.isEmpty())
                           {
                              cache.put(new NullNodeData(parentData, pattern.getQPathEntry()));
                           }
                           else
                           {
                              cache.put(persistedNodeData.get(0));
                           }
                        }
                        else
                        {
                           cache.addChildNodes(parent, pattern, persistedNodeData);
                        }
                        for (NodeData node : persistedItemList)
                        {
                           childNodesMap.put(node.getIdentifier(), node);
                        }
                     }
                  }
               }
            }
         }
         finally
         {
            for (DataRequest rq : requests)
            {
               rq.done();
            }
            requests.clear();
         }
      }

      return new ArrayList<NodeData>(childNodesMap.values());
   }

   /**
    * Get referenced properties data.
    * 
    * @param identifier
    *          referenceable identifier
    * @return List<PropertyData>
    * @throws RepositoryException
    *           Repository error
    */
   protected List<PropertyData> getReferencedPropertiesData(String identifier) throws RepositoryException
   {
      List<PropertyData> refProps = null;
      if (cache.isEnabled())
      {
         refProps = cache.getReferencedProperties(identifier);
         if (refProps != null)
         {
            return refProps;
         }
      }
      final DataRequest request = new DataRequest(identifier, DataRequest.GET_REFERENCES);

      try
      {
         request.start();
         if (cache.isEnabled())
         {
            // Try first to get the value from the cache since a
            // request could have been launched just before
            refProps = cache.getReferencedProperties(identifier);
            if (refProps != null)
            {
               return refProps;
            }
         }
         refProps = super.getReferencesData(identifier, false);
         if (cache.isEnabled())
         {
            cache.addReferencedProperties(identifier, refProps);
         }
         return refProps;
      }
      finally
      {
         request.done();
      }
   }

   /**
    * Get child PropertyData.
    * 
    * @param nodeData
    *          parent
    * @param forcePersistentRead
    *          true if persistent read is required (without cache)
    * @return List<PropertyData>
    * @throws RepositoryException
    *           Repository error
    */
   protected List<PropertyData> getChildPropertiesData(NodeData nodeData, boolean forcePersistentRead)
      throws RepositoryException
   {

      List<PropertyData> childProperties = null;
      if (!forcePersistentRead && cache.isEnabled())
      {
         childProperties = cache.getChildProperties(nodeData);
         if (childProperties != null)
         {
            return childProperties;
         }
      }
      final DataRequest request = new DataRequest(nodeData.getIdentifier(), DataRequest.GET_PROPERTIES);

      try
      {
         request.start();
         if (!forcePersistentRead && cache.isEnabled())
         {
            // Try first to get the value from the cache since a
            // request could have been launched just before
            childProperties = cache.getChildProperties(nodeData);
            if (childProperties != null)
            {
               return childProperties;
            }
         }

         childProperties = super.getChildPropertiesData(nodeData);
         // TODO childProperties.size() > 0 for SDB
         if (childProperties.size() > 0 && cache.isEnabled())
         {
            NodeData parentData = (NodeData)getItemData(nodeData.getIdentifier());

            if (parentData != null)
            {
               cache.addChildProperties(parentData, childProperties);
            }
         }
         return childProperties;
      }
      finally
      {
         request.done();
      }
   }

   protected List<PropertyData> getChildPropertiesDataByPattern(NodeData nodeData, List<QPathEntryFilter> patternFilters)
      throws RepositoryException
   {
      if (!cache.isEnabled())
      {
         return super.getChildPropertiesData(nodeData, patternFilters);
      }

      if (!cache.isPatternSupported())
      {
         return getChildPropertiesData(nodeData);
      }

      // 1. check cache - outside data request
      List<PropertyData> childPropsList = cache.getChildProperties(nodeData);
      if (childPropsList != null)
      {
         return childPropsList;
      }

      Map<String, PropertyData> childPropsMap = new HashMap<String, PropertyData>();

      Set<QPathEntryFilter> uncachedPatterns = new HashSet<QPathEntryFilter>();
      for (int i = 0; i < patternFilters.size(); i++)
      {
         if (patternFilters.get(i).isExactName())
         {
            ItemData data = getCachedItemData(nodeData, patternFilters.get(i).getQPathEntry(), ItemType.PROPERTY);
            if (data != null)
            {
               if (!(data instanceof NullPropertyData))
               {
                  childPropsMap.put(data.getIdentifier(), (PropertyData)data);
               }
            }
            else
            {
               uncachedPatterns.add(patternFilters.get(i));
            }
         }
         else
         {

            // get property list by pattern
            List<PropertyData> cachedItemList = cache.getChildProperties(nodeData, patternFilters.get(i));
            if (cachedItemList != null)
            {
               //merge results
               for (int j = 0, length = cachedItemList.size(); j < length; j++)
               {
                  childPropsMap.put(cachedItemList.get(j).getIdentifier(), cachedItemList.get(j));
               }
            }
            else
            {
               uncachedPatterns.add(patternFilters.get(i));
            }
         }
      }

      // 2. check cache - inside data requests
      if (!uncachedPatterns.isEmpty())
      {
         List<DataRequest> requests = new ArrayList<DataRequest>();
         try
         {

            final DataRequest request = new DataRequest(nodeData.getIdentifier(), DataRequest.GET_PROPERTIES);
            request.start();
            requests.add(request);

            // Try first to get the value from the cache since a
            // request could have been launched just before
            childPropsList = cache.getChildProperties(nodeData);
            if (childPropsList != null)
            {
               return childPropsList;
            }

            Iterator<QPathEntryFilter> patternIterator = uncachedPatterns.iterator();
            while (patternIterator.hasNext())
            {
               QPathEntryFilter pattern = patternIterator.next();
               if (pattern.isExactName())
               {
                  DataRequest exactNameRequest = new DataRequest(nodeData.getIdentifier(), pattern.getQPathEntry());
                  exactNameRequest.start();
                  requests.add(exactNameRequest);

                  ItemData data = getCachedItemData(nodeData, pattern.getQPathEntry(), ItemType.PROPERTY);
                  if (data != null)
                  {
                     if (!(data instanceof NullPropertyData))
                     {
                        childPropsMap.put(data.getIdentifier(), (PropertyData)data);
                     }
                     patternIterator.remove();
                  }
               }
               else
               {
                  // get properties list by pattern
                  List<PropertyData> cachedItemList = cache.getChildProperties(nodeData, pattern);
                  if (cachedItemList != null)
                  {
                     //merge results
                     for (int j = 0, length = cachedItemList.size(); j < length; j++)
                     {
                        childPropsMap.put(cachedItemList.get(j).getIdentifier(), cachedItemList.get(j));
                     }
                     patternIterator.remove();
                  }
               }
            }
            patternIterator = null;

            // execute all patterns and put result in cache
            if (!uncachedPatterns.isEmpty())
            {
               List<PropertyData> persistedItemList =
                  super.getChildPropertiesData(nodeData, new ArrayList<QPathEntryFilter>(uncachedPatterns));

               if (persistedItemList.size() > 0)
               {
                  NodeData parent = (NodeData)getItemData(nodeData.getIdentifier());
                  if (parent != null)
                  {
                     // filter properties list for each exact name
                     patternIterator = uncachedPatterns.iterator();
                     while (patternIterator.hasNext())
                     {
                        QPathEntryFilter pattern = patternIterator.next();
                        List<PropertyData> persistedPropData = (List<PropertyData>)pattern.accept(persistedItemList);
                        if (pattern.isExactName())
                        {
                           if (persistedPropData.isEmpty())
                           {
                              cache.put(new NullPropertyData(parent, pattern.getQPathEntry()));
                           }
                           else
                           {
                              cache.put(persistedPropData.get(0));
                           }
                        }
                        else
                        {
                           cache.addChildProperties(parent, pattern, persistedPropData);
                        }

                        for (PropertyData node : persistedItemList)
                        {
                           childPropsMap.put(node.getIdentifier(), node);
                        }
                     }
                  }
               }
            }
         }
         finally
         {
            for (DataRequest rq : requests)
            {
               rq.done();
            }
            requests.clear();
         }
      }

      return new ArrayList<PropertyData>(childPropsMap.values());
   }

   /**
    * Get persisted ItemData.
    * 
    * @param parentData
    *          parent
    * @param name
    *          Item name
    * @param itemType
    *          item type         
    * @return ItemData
    * @throws RepositoryException
    *           error
    */
   protected ItemData getPersistedItemData(NodeData parentData, QPathEntry name, ItemType itemType)
      throws RepositoryException
   {
      ItemData data = super.getItemData(parentData, name, itemType);
      if (cache.isEnabled())
      {
         if (data == null)
         {
            if (itemType == ItemType.NODE || itemType == ItemType.UNKNOWN)
            {
               cache.put(new NullNodeData(parentData, name));
            }
            else
            {
               cache.put(new NullPropertyData(parentData, name));
            }
         }
         else
         {
            cache.put(data);
         }
      }
      return data;
   }

   /**
    * Call
    * {@link org.exoplatform.services.jcr.impl.dataflow.persistent.WorkspacePersistentDataManager#getItemData(java.lang.String)
    * WorkspaceDataManager.getItemDataByIdentifier(java.lang.String)} and cache result if non null returned.
    * 
    * @see org.exoplatform.services.jcr.impl.dataflow.persistent.WorkspacePersistentDataManager#getItemData(java.lang.String)
    */
   protected ItemData getPersistedItemData(String identifier) throws RepositoryException
   {
      ItemData data = super.getItemData(identifier);
      if (cache.isEnabled())
      {
         if (data != null)
         {
            cache.put(data);
         }
         else if (identifier != null)
         {
            // no matter does property or node expected - store NullNodeData
            cache.put(new NullNodeData(identifier));
         }
      }
      return data;
   }

   /**
    * Get child PropertyData list (without ValueData).
    * 
    * @param nodeData
    *          parent
    * @param forcePersistentRead
    *          true if persistent read is required (without cache)
    * @return List<PropertyData>
    * @throws RepositoryException
    *           Repository error
    */
   protected List<PropertyData> listChildPropertiesData(NodeData nodeData, boolean forcePersistentRead)
      throws RepositoryException
   {

      List<PropertyData> propertiesList;
      if (!forcePersistentRead && cache.isEnabled())
      {
         propertiesList = cache.listChildProperties(nodeData);
         if (propertiesList != null)
         {
            return propertiesList;
         }
      }

      final DataRequest request = new DataRequest(nodeData.getIdentifier(), DataRequest.GET_LIST_PROPERTIES);
      try
      {
         request.start();
         if (!forcePersistentRead && cache.isEnabled())
         {
            // Try first to get the value from the cache since a
            // request could have been launched just before
            propertiesList = cache.listChildProperties(nodeData);
            if (propertiesList != null)
            {
               return propertiesList;
            }
         }
         propertiesList = super.listChildPropertiesData(nodeData);
         // TODO propertiesList.size() > 0 for SDB
         if (propertiesList.size() > 0 && cache.isEnabled())
         {
            NodeData parentData = (NodeData)getItemData(nodeData.getIdentifier());

            if (parentData != null)
            {
               cache.addChildPropertiesList(parentData, propertiesList);
            }
         }
         return propertiesList;
      }
      finally
      {
         request.done();
      }
   }

   protected boolean isTxAware()
   {
      return transactionManager != null;
   }

   /**
    * Fix Property BLOB Values if someone has null file (swap actually) 
    * by reading the content from the storage (VS or JDBC no matter).
    * 
    * @param prop PropertyData
    * @throws RepositoryException
    */
   protected void fixPropertyValues(PropertyData prop) throws RepositoryException
   {
      final List<ValueData> vals = prop.getValues();
      for (int i = 0; i < vals.size(); i++)
      {
         ValueData vd = vals.get(i);
         if (!vd.isByteArray())
         {
            // check if file is correct
            FilePersistedValueData fpvd = (FilePersistedValueData)vd;
            if (fpvd.getFile() == null)
            {
               // need read from storage
               ValueData svd = getPropertyValue(prop.getIdentifier(), vd.getOrderNumber(), prop.getPersistedVersion());

               if (svd == null)
               {
                  // error, value not found
                  throw new RepositoryException("Value cannot be found in storage for cached Property "
                     + prop.getQPath().getAsString() + ", orderNumb:" + vd.getOrderNumber() + ", pversion:"
                     + prop.getPersistedVersion());
               }

               vals.set(i, svd);
            }
         }
      }
   }

   /**
    * Fill Property Value from persistent storage.
    * 
    * @param prop PropertyData, original Property data
    * @return PropertyData
    * @throws IllegalStateException
    * @throws RepositoryException 
    */
   protected ValueData getPropertyValue(String propertyId, int orderNumb, int persistedVersion)
      throws IllegalStateException, RepositoryException
   {
      // TODO use interface not JDBC
      JDBCStorageConnection conn = (JDBCStorageConnection)dataContainer.openConnection();
      try
      {
         return conn.getValue(propertyId, orderNumb, persistedVersion);
      }
      finally
      {
         conn.close();
      }
   }

   /**
    * {@inheritDoc}
    */
   public void suspend() throws SuspendException
   {
      isResponsibleForResuming = true;

      if (rpcService != null)
      {
         try
         {
            rpcService.executeCommandOnAllNodes(suspend, true);
         }
         catch (SecurityException e)
         {
            throw new SuspendException(e);
         }
         catch (RPCException e)
         {
            throw new SuspendException(e);
         }
      }
      else
      {
         suspendLocally();
      }
   }

   /**
    * {@inheritDoc}
    */
   public void resume() throws ResumeException
   {
      if (rpcService != null)
      {
         try
         {
            rpcService.executeCommandOnAllNodes(resume, true);
         }
         catch (SecurityException e)
         {
            throw new ResumeException(e);
         }
         catch (RPCException e)
         {
            throw new ResumeException(e);
         }
      }
      else
      {
         resumeLocally();
      }

      isResponsibleForResuming = false;
   }

   /**
    * {@inheritDoc}
    */
   public boolean isSuspended()
   {
      return isSuspended;
   }

   private void suspendLocally() throws SuspendException
   {
      if (isSuspended)
      {
         throw new SuspendException("Component already suspended.");
      }

      latcher = new CountDownLatch(1);
      isSuspended = true;

      while (workingThreads.get() != 0)
      {
         try
         {
            Thread.sleep(50);
         }
         catch (InterruptedException e)
         {
            throw new SuspendException(e);
         }
      }
   }

   private void resumeLocally() throws ResumeException
   {
      if (!isSuspended)
      {
         throw new ResumeException("Component is not suspended.");
      }

      latcher.countDown();
      isSuspended = false;
   }

   /**
    * {@inheritDoc}
    */
   public void onChange(TopologyChangeEvent event)
   {
      if (isSuspended)
      {
         new Thread()
         {
            @Override
            public synchronized void run()
            {
               try
               {
                  List<Object> results = rpcService.executeCommandOnAllNodes(requestForResponsibleForResuming, true);

                  for (Object result : results)
                  {
                     if ((Boolean)result)
                     {
                        return;
                     }
                  }

                  // node which was responsible for resuming leave the cluster, so resume component
                  try
                  {
                     resumeLocally();
                  }
                  catch (ResumeException e)
                  {
                     LOG.error("Can not resume component", e);
                  }
               }
               catch (SecurityException e1)
               {
                  LOG.error("You haven't privileges to execute remote command", e1);
               }
               catch (RPCException e1)
               {
                  LOG.error("Exception during command execution", e1);
               }
            }
         }.start();
      }
   }

   /**
    * Initialization remote commands.
    */
   private void doInitRemoteCommands()
   {
      if (rpcService != null)
      {
         // register commands
         suspend = rpcService.registerCommand(new RemoteCommand()
         {

            public String getId()
            {
               return "org.exoplatform.services.jcr.impl.dataflow.persistent.CacheableWorkspaceDataManager-suspend-"
                  + dataContainer.getUniqueName();
            }

            public Serializable execute(Serializable[] args) throws Throwable
            {
               suspendLocally();
               return null;
            }
         });

         resume = rpcService.registerCommand(new RemoteCommand()
         {

            public String getId()
            {
               return "org.exoplatform.services.jcr.impl.dataflow.persistent.CacheableWorkspaceDataManager-resume-"
                  + dataContainer.getUniqueName();
            }

            public Serializable execute(Serializable[] args) throws Throwable
            {
               resumeLocally();
               return null;
            }
         });

         requestForResponsibleForResuming = rpcService.registerCommand(new RemoteCommand()
         {

            public String getId()
            {
               return "org.exoplatform.services.jcr.impl.dataflow.persistent.CacheableWorkspaceDataManager"
                        + "-requestForResponsibilityForResuming-" + dataContainer.getUniqueName();
            }

            public Serializable execute(Serializable[] args) throws Throwable
            {
               return isResponsibleForResuming;
            }
         });

         rpcService.registerTopologyChangeListener(this);
      }
   }
}