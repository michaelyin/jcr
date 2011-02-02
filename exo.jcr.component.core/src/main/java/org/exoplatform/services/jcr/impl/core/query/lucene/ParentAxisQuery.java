/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.exoplatform.services.jcr.impl.core.query.lucene;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.HitCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Searcher;
import org.apache.lucene.search.Similarity;
import org.apache.lucene.search.Weight;
import org.exoplatform.services.jcr.datamodel.InternalQName;
import org.exoplatform.services.jcr.impl.core.query.lucene.hits.Hits;
import org.exoplatform.services.jcr.impl.core.query.lucene.hits.ScorerHits;

import java.io.IOException;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * <code>ParentAxisQuery</code> selects the parent nodes of a context query.
 */
class ParentAxisQuery extends Query
{

   /**
    * Default score is 1.0f.
    */
   private static final Float DEFAULT_SCORE = new Float(1.0f);

   /**
    * The context query
    */
   private final Query contextQuery;

   /**
    * The nameTest to apply on the parent axis, or <code>null</code> if any
    * parent node should be selected.
    */
   private final InternalQName nameTest;

   /**
    * The index format version.
    */
   private final IndexFormatVersion version;

   /**
    * The internal namespace mappings.
    */
   private final NamespaceMappings nsMappings;

   /**
    * The scorer of the context query
    */
   private Scorer contextScorer;

   /**
    * Creates a new <code>ParentAxisQuery</code> based on a
    * <code>context</code> query.
    *
    * @param context  the context for this query.
    * @param nameTest a name test or <code>null</code> if any parent node is
    *                 selected.
    * @param version the index format version.
    * @param nsMappings the internal namespace mappings.
    */
   ParentAxisQuery(Query context, InternalQName nameTest, IndexFormatVersion version, NamespaceMappings nsMappings)
   {
      this.contextQuery = context;
      this.nameTest = nameTest;
      this.version = version;
      this.nsMappings = nsMappings;
   }

   /**
    * Creates a <code>Weight</code> instance for this query.
    *
    * @param searcher the <code>Searcher</code> instance to use.
    * @return a <code>ParentAxisWeight</code>.
    */
   @Override
   public Weight createWeight(Searcher searcher)
   {
      return new ParentAxisWeight(searcher);
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public void extractTerms(Set terms)
   {
      contextQuery.extractTerms(terms);
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public Query rewrite(IndexReader reader) throws IOException
   {
      Query cQuery = contextQuery.rewrite(reader);
      if (cQuery == contextQuery)
      {
         return this;
      }
      else
      {
         return new ParentAxisQuery(cQuery, nameTest, version, nsMappings);
      }
   }

   /**
    * Always returns 'ParentAxisQuery'.
    *
    * @param field the name of a field.
    * @return 'ParentAxisQuery'.
    */
   @Override
   public String toString(String field)
   {
      return "ParentAxisQuery " + "nameTest=" + (nameTest != null ? nameTest.toString() : "null") + " contextQuery="
         + contextQuery.toString();
   }

   //-----------------------< ParentAxisWeight >-------------------------------

   /**
    * The <code>Weight</code> implementation for this <code>ParentAxisQuery</code>.
    */
   private class ParentAxisWeight extends Weight
   {

      /**
       * The searcher in use
       */
      private final Searcher searcher;

      /**
       * Creates a new <code>ParentAxisWeight</code> instance using
       * <code>searcher</code>.
       *
       * @param searcher a <code>Searcher</code> instance.
       */
      private ParentAxisWeight(Searcher searcher)
      {
         this.searcher = searcher;
      }

      /**
       * Returns this <code>ParentAxisQuery</code>.
       *
       * @return this <code>ParentAxisQuery</code>.
       */
      @Override
      public Query getQuery()
      {
         return ParentAxisQuery.this;
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public float getValue()
      {
         return 1.0f;
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public float sumOfSquaredWeights() throws IOException
      {
         return 1.0f;
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public void normalize(float norm)
      {
      }

      /**
       * Creates a scorer for this <code>ParentAxisQuery</code>.
       *
       * @param reader a reader for accessing the index.
       * @return a <code>ParentAxisScorer</code>.
       * @throws IOException if an error occurs while reading from the index.
       */
      @Override
      public Scorer scorer(IndexReader reader, boolean scoreDocsInOrder, boolean topScorer) throws IOException
      {
         contextScorer = contextQuery.weight(searcher).scorer(reader, scoreDocsInOrder, topScorer);
         HierarchyResolver resolver = (HierarchyResolver)reader;
         return new ParentAxisScorer(searcher.getSimilarity(), reader, searcher, resolver);
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public Explanation explain(IndexReader reader, int doc) throws IOException
      {
         return new Explanation();
      }
   }

   //--------------------------< ParentAxisScorer >----------------------------

   /**
    * Implements a <code>Scorer</code> for this <code>ParentAxisQuery</code>.
    */
   private class ParentAxisScorer extends Scorer
   {

      /**
       * An <code>IndexReader</code> to access the index.
       */
      private final IndexReader reader;

      /**
       * The <code>HierarchyResolver</code> of the index.
       */
      private final HierarchyResolver hResolver;

      /**
       * The searcher instance.
       */
      private final Searcher searcher;

      /**
       * BitSet storing the id's of selected documents
       */
      private BitSet hits;

      /**
       * Map that contains the scores from matching documents from the context
       * query. To save memory only scores that are not equal to 1.0f are put
       * to this map.
       * <p/>
       * key=[Integer] id of selected document from context query<br>
       * value=[Float] score for that document
       */
      private final Map scores = new HashMap();

      /**
       * The next document id to return
       */
      private int nextDoc = -1;

      /**
       * Creates a new <code>ParentAxisScorer</code>.
       *
       * @param similarity the <code>Similarity</code> instance to use.
       * @param reader     for index access.
       * @param searcher   the index searcher.
       * @param resolver   the hierarchy resolver.
       */
      protected ParentAxisScorer(Similarity similarity, IndexReader reader, Searcher searcher,
         HierarchyResolver resolver)
      {
         super(similarity);
         this.reader = reader;
         this.searcher = searcher;
         this.hResolver = resolver;
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public boolean next() throws IOException
      {
         calculateParent();
         nextDoc = hits.nextSetBit(nextDoc + 1);
         return nextDoc > -1;
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public int doc()
      {
         return nextDoc;
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public float score() throws IOException
      {
         Float score = (Float)scores.get(new Integer(nextDoc));
         if (score == null)
         {
            score = DEFAULT_SCORE;
         }
         return score.floatValue();
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public boolean skipTo(int target) throws IOException
      {
         calculateParent();
         nextDoc = hits.nextSetBit(target);
         return nextDoc > -1;
      }

      /**
       * {@inheritDoc}
       *
       * @throws UnsupportedOperationException this implementation always
       *                                       throws an <code>UnsupportedOperationException</code>.
       */
      @Override
      public Explanation explain(int doc) throws IOException
      {
         throw new UnsupportedOperationException();
      }

      private void calculateParent() throws IOException
      {
         if (hits == null)
         {
            hits = new BitSet(reader.maxDoc());

            final IOException[] ex = new IOException[1];
            contextScorer.score(new HitCollector()
            {

               private int[] docs = new int[1];

               @Override
               public void collect(int doc, float score)
               {
                  try
                  {
                     docs = hResolver.getParents(doc, docs);
                     if (docs.length == 1)
                     {
                        // optimize single value
                        hits.set(docs[0]);
                        if (score != DEFAULT_SCORE.floatValue())
                        {
                           scores.put(new Integer(docs[0]), new Float(score));
                        }
                     }
                     else
                     {
                        for (int i = 0; i < docs.length; i++)
                        {
                           hits.set(docs[i]);
                           if (score != DEFAULT_SCORE.floatValue())
                           {
                              scores.put(new Integer(docs[i]), new Float(score));
                           }
                        }
                     }
                  }
                  catch (IOException e)
                  {
                     ex[0] = e;
                  }
               }
            });

            if (ex[0] != null)
            {
               throw ex[0];
            }

            // filter out documents that do not match the name test
            if (nameTest != null)
            {
               Query nameQuery = new NameQuery(nameTest, version, nsMappings);
               Hits nameHits = new ScorerHits(nameQuery.weight(searcher).scorer(reader, true, false));
               for (int i = hits.nextSetBit(0); i >= 0; i = hits.nextSetBit(i + 1))
               {
                  int doc = nameHits.skipTo(i);
                  if (doc == -1)
                  {
                     // no more name tests, clear remaining
                     hits.clear(i, hits.length());
                  }
                  else
                  {
                     // assert doc >= i
                     if (doc > i)
                     {
                        // clear hits
                        hits.clear(i, doc);
                        i = doc;
                     }
                  }
               }
            }
         }
      }
   }
}
