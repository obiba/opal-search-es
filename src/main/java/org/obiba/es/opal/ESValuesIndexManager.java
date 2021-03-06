/*
 * Copyright (c) 2018 OBiBa. All rights reserved.
 *
 * This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.obiba.es.opal;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.obiba.es.opal.mapping.MappingHelper;
import org.obiba.es.opal.mapping.ValueTableMapping;
import org.obiba.es.opal.support.ESIndexManager;
import org.obiba.es.opal.support.ESMapping;
import org.obiba.magma.Value;
import org.obiba.magma.ValueTable;
import org.obiba.magma.Variable;
import org.obiba.magma.VariableEntity;
import org.obiba.magma.concurrent.ConcurrentValueTableReader;
import org.obiba.magma.concurrent.ConcurrentValueTableReader.ConcurrentReaderCallback;
import org.obiba.magma.support.VariableNature;
import org.obiba.magma.type.BinaryType;
import org.obiba.magma.type.DateType;
import org.obiba.opal.spi.search.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class ESValuesIndexManager extends ESIndexManager implements ValuesIndexManager {

  private static final Logger log = LoggerFactory.getLogger(ESValuesIndexManager.class);

  private final ThreadFactory threadFactory;

  private final VariableSummaryHandler variableSummaryHandler;

  protected ESValuesIndexManager(ESSearchService esSearchService, VariableSummaryHandler variableSummaryHandler, ThreadFactory threadFactory) {
    super(esSearchService);
    this.variableSummaryHandler = variableSummaryHandler;
    this.threadFactory = threadFactory;
  }

  @NotNull
  @Override
  public ValueTableValuesIndex getIndex(@NotNull ValueTable vt) {
    return (ESValueTableValuesIndex) super.getIndex(vt);
  }

  @Override
  protected ValueTableIndex createIndex(@NotNull ValueTable vt) {
    return new ESValueTableValuesIndex(vt);
  }

  @NotNull
  @Override
  public IndexSynchronization createSyncTask(ValueTable valueTable, ValueTableIndex index) {
    return new Indexer(valueTable, (ESValueTableValuesIndex) index);
  }

  @NotNull
  @Override
  public String getName() {
    return esIndexName() + "-values";
  }

  private class Indexer extends ESIndexer {

    private final ESValueTableValuesIndex index;

    private Indexer(ValueTable table, ESValueTableValuesIndex index) {
      super(table, index);
      this.index = index;
    }

    @Override
    protected void index() {
      ConcurrentValueTableReader.Builder.newReader() //
          .withThreads(threadFactory) //
          .ignoreReadErrors() //
          .from(valueTable) //
          .variablesFilter(index.getVariables()) //
          .to(new ValuesReaderCallback()) //
          .build() //
          .read();
    }

    private class ValuesReaderCallback implements ConcurrentReaderCallback {

      private BulkRequestBuilder bulkRequest = esSearchService.getClient().prepareBulk();

      private final Map<Variable, VariableNature> natures = new HashMap<>();

      private final Stopwatch stopwatch = Stopwatch.createUnstarted();

      @Override
      public void onBegin(List<VariableEntity> entitiesToCopy, Variable... variables) {
        stopwatch.start();
        for(Variable variable : variables) {
          natures.put(variable, VariableNature.getNature(variable));
        }
      }

      @Override
      public void onValues(VariableEntity entity, Variable[] variables, Value... values) {
        if(stop) {
          return;
        }

        String identifier = entity.getIdentifier();
        bulkRequest.add(esSearchService.getClient() //
            .prepareIndex(getName(), valueTable.getEntityType(), identifier) //
            .setSource("{\"identifier\":\"" + identifier + "\"}"));

        try {
          XContentBuilder builder = XContentFactory.jsonBuilder().startObject();
          builder.field("identifier", identifier);
          builder.field("project", valueTable.getDatasource().getName());
          builder.field("datasource", valueTable.getDatasource().getName());
          builder.field("table", valueTable.getName());
          builder.field("reference", valueTable.getTableReference());
          builder.field("entityType", valueTable.getEntityType());

          for(int i = 0; i < variables.length; i++) {
            indexValue(builder, variables[i], values[i], identifier);
          }
          builder.endObject();

          IndexRequestBuilder requestBuilder = esSearchService.getClient()
              .prepareIndex(index.getIndexName(), index.getIndexType(), valueTable.getTableReference() + "-" + identifier).setParent(identifier).setSource(builder);
          bulkRequest.add(requestBuilder);
          done++;

          if(bulkRequest.numberOfActions() >= ES_BATCH_SIZE) {
            bulkRequest = sendAndCheck(bulkRequest);
          }
        } catch(IOException e) {
          throw new RuntimeException(e);
        }
      }

      private void indexValue(XContentBuilder xcb, Variable variable, Value value, String identifier) throws IOException {
        String fieldName = index.getFieldName(variable);

        if(value.isSequence() && !value.isNull()) {
          List<Object> values = Lists.newArrayList();

          for(Value v : value.asSequence().getValue()) {
            values.add(esValue(variable, v));
          }

          xcb.field(fieldName, values);
        } else {
          xcb.field(fieldName, esValue(variable, value));
        }

        variableSummaryHandler.stackVariable(getValueTable(), variable, value);
      }

      @Override
      public void onComplete() {
        stopwatch.stop();
        if(stop) {
          index.delete();
          variableSummaryHandler.clearComputingSummaries(getValueTable());
        } else {
          sendAndCheck(bulkRequest);
          index.updateTimestamps();
          log.info("Indexed table {} in {}", getValueTable().getTableReference(), stopwatch);

          // compute summaries in a new thread
          new Thread(new Runnable() {
            @Override
            public void run() {
              variableSummaryHandler.computeSummaries(getValueTable());
            }
          }).start();
        }
      }

      @Override
      public boolean isCancelled() {
        return stop;
      }

      /**
       * OPAL-1158: missing values are indexed as null for continuous variables
       *
       * @param variable the variable
       * @param value the value
       * @return an object
       */
      @Nullable
      private Object esValue(Variable variable, Value value) {
        switch(natures.get(variable)) {
          case CONTINUOUS:
            if(variable.isMissingValue(value)) {
              return null;
            }
        }
        if(value.isNull()) return null;
        Object obj = value.getValue();
        if(value.getValueType() == DateType.get()) {
          return obj.toString(); // ie MagmaDate.toString()
        }
        return obj;
      }
    }

  }

  private class ESValueTableValuesIndex extends ESValueTableIndex implements ValueTableValuesIndex {

    private ESValueTableValuesIndex(ValueTable vt) {
      super(vt);
    }

    @Override
    public String getIndexName() {
      return super.getIndexName();
    }

    @Override
    public String getIndexType() {
      return "ValueSet";
    }

    @Override
    public String getFieldName(Variable variable) {
      return MappingHelper.toFieldName(getValueTableReference(), variable);
    }

    @Override
    public String getFieldName(String variableName) {
      ValueTable table = resolveTable();
      return MappingHelper.toFieldName(table.getTableReference(), table.getVariable(variableName));
    }

    @Override
    protected XContentBuilder createMapping() {
      return ValueTableMapping.createMapping(getIndexType(), resolveTable());
    }

    @Override
    protected XContentBuilder updateMapping(ESMapping mapping) {
      return ValueTableMapping.updateMapping(resolveTable(), mapping);
    }

    @Override
    public Iterable<Variable> getVariables() {
      // Do not index binary values, do not even extract the binary values
      return StreamSupport.stream(resolveTable().getVariables().spliterator(), false)
          .filter(variable -> !variable.getValueType().isGeo() && !BinaryType.get().equals(variable.getValueType()))
          .collect(Collectors.toList());
    }
  }
}
