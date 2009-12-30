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
package org.exoplatform.services.jcr.webdav.util;

import org.exoplatform.services.jcr.webdav.WebDavConst;

import java.util.ArrayList;
import java.util.List;

import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.jcr.nodetype.NodeType;

/**
 * Created by The eXo Platform SARL Author : <a
 * href="gavrikvetal@gmail.com">Vitaly Guly</a>.
 * 
 * @version $Id: $
 */

public class NodeTypeUtil
{

   /**
    * Returns the NodeType of node according to the File-NodeType header.
    * 
    * @param fileNodeTypeHeader File-NodeType header
    * @return Nodetype
    * @throws NoSuchNodeTypeException {@link NoSuchNodeTypeException}
    */
   public static String getFileNodeType(String fileNodeTypeHeader) throws NoSuchNodeTypeException
   {
      if (fileNodeTypeHeader != null)
         return fileNodeTypeHeader;
      else
         // Default nodetype for the file.
         return WebDavConst.NodeTypes.NT_FILE;
   }

   /**
    * Returns the NodeType of content node according to the Content-NodeType
    * header.
    * 
    * @param contentNodeTypeHeader Content-NodeType header
    * @return Nodetype
    */
   public static String getContentNodeType(String contentNodeTypeHeader)
   {
      if (contentNodeTypeHeader != null)
         return contentNodeTypeHeader;
      else
         return WebDavConst.NodeTypes.NT_RESOURCE;
   }

   /**
    * Cheks if the NodeType of content node extends nt:resource.
    * 
    * @param contentNodeType Content-NodeType header
    * @throws NoSuchNodeTypeException {@link NoSuchNodeTypeException}
    */
   public static void checkContentResourceType(NodeType contentNodeType) throws NoSuchNodeTypeException
   {
      if (!contentNodeType.isNodeType(WebDavConst.NodeTypes.NT_RESOURCE))
      {
         throw new NoSuchNodeTypeException("Content-Node type " + contentNodeType.getName()
            + " must extend nt:resource.");
      }
   }

   /**
    * Returns the list of node mixins.
    * 
    * @param mixinTypes list of mixins or null
    * @return list of mixins
    */
   public static ArrayList<String> getMixinTypes(List<String> mixinTypes)
   {
      ArrayList<String> mixins = new ArrayList<String>();
      if (mixinTypes == null)
      {
         return mixins;
      }
      return mixins;
   }

}
