/*
 * Copyright (c) 2018 OBiBa. All rights reserved.
 *
 * This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.obiba.es.opal.support;

import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.obiba.magma.NoSuchVariableException;
import org.obiba.magma.support.MagmaEngineVariableResolver;
import org.obiba.magma.support.VariableNature;
import org.obiba.opal.spi.search.support.ValueTableIndexManager;
import org.obiba.opal.web.model.Search;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts a DTO query to an elastic search JSON query
 */
class QueryTermConverter {

  private final Map<String, ValueTableIndexManager> valueTableIndexManagers = Maps.newLinkedHashMap();

  private final int termsFacetSize;

  /**
   * @param valueTableIndexManager - ValueTableIndexManager provides certain variable information required for conversion
   * @param termsFacetSize - used to limit the 'terms' facet results
   */
  QueryTermConverter(ValueTableIndexManager valueTableIndexManager, int termsFacetSize) {
    this.valueTableIndexManagers.put(valueTableIndexManager.getReference(), valueTableIndexManager);
    this.termsFacetSize = termsFacetSize;
  }

  /**
   * Converts a DTO query to an elastic search JSON query
   *
   * @param dtoQueries
   * @return
   * @throws JSONException
   */
  JSONObject convert(Search.QueryTermsDto dtoQueries) throws JSONException {
    JSONObject jsonAggregations = new JSONObject();
    for(Search.QueryTermDto dtoQuery : dtoQueries.getQueriesList()) {
      JSONObject jsonAggregation = new JSONObject();
      if(dtoQuery.hasExtension(Search.LogicalTermDto.filter)) {
        convertLogicalFilter("filter", dtoQuery.getExtension(Search.LogicalTermDto.filter), jsonAggregation);
      } else if(dtoQuery.hasExtension(Search.LogicalTermDto.facetFilter)) {
        convertFilter(dtoQuery, jsonAggregation);
      } else if(dtoQuery.hasExtension(Search.VariableTermDto.field)) {
        convertField(dtoQuery.getExtension(Search.VariableTermDto.field), jsonAggregation);
      } else if(dtoQuery.hasGlobal()) {
        convertGlobal(dtoQuery, jsonAggregation);
      }
      jsonAggregations.put(dtoQuery.getFacet(), jsonAggregation);
    }

    // get the query string after the aggregations have been inspected
    JSONObject jsonQuery = new JSONObject("{\"query\":{\"query_string\":{\"query\":\"" + getQueryString() + "\"}}, \"size\":0}");
    jsonQuery.put("aggregations", jsonAggregations);

    return jsonQuery;
  }

  private void convertLogicalFilter(String filterName, Search.LogicalTermDto dtoLogicalFilter,
      JSONObject jsonAggregation) throws JSONException {
    Search.TermOperator operator = dtoLogicalFilter.getOperator();
    String operatorName = operator == Search.TermOperator.AND_OP ? "and" : "or";
    JSONObject jsonOperator = new JSONObject();

    List<Search.FilterDto> filters = dtoLogicalFilter.getExtension(Search.FilterDto.filters);

    if(filters.size() > 1) {
      for(Search.FilterDto filter : filters) {
        jsonOperator.accumulate(operatorName, convertFilterType(filter));
      }

      jsonAggregation.put(filterName, jsonOperator);
    } else if(filters.size() == 1) {
      jsonAggregation.put(filterName, convertFilterType(filters.get(0)));
    }
  }

  private void convertFilter(Search.QueryTermDto dtoQuery, JSONObject jsonAggregation)
      throws JSONException, UnsupportedOperationException {
    convertLogicalFilter("filter", dtoQuery.getExtension(Search.LogicalTermDto.facetFilter), jsonAggregation);
    if(dtoQuery.hasExtension(Search.VariableTermDto.field)) {
      convertNestedField(dtoQuery.getExtension(Search.VariableTermDto.field), jsonAggregation);
    }
  }

  private void convertGlobal(Search.QueryTermDto dtoQuery, JSONObject jsonAggregation)
      throws JSONException, UnsupportedOperationException {
    jsonAggregation.put("global", new JSONObject());
    if(dtoQuery.hasExtension(Search.VariableTermDto.field)) {
      convertNestedField(dtoQuery.getExtension(Search.VariableTermDto.field), jsonAggregation);
    }
  }

  private void convertNestedField(Search.VariableTermDto dtoVariable, JSONObject jsonAggregation)
      throws JSONException, UnsupportedOperationException {
    JSONObject jsonAgg = new JSONObject();
    convertField(dtoVariable, jsonAgg);
    JSONObject jsonAggregation2 = new JSONObject();
    jsonAggregation2.put("0", jsonAgg);
    jsonAggregation.put("aggregations", jsonAggregation2);
  }

  private void convertField(Search.VariableTermDto dtoVariable, JSONObject jsonAggregation)
      throws JSONException, UnsupportedOperationException {
    if(dtoVariable.hasType()) {
      convertFieldByType(dtoVariable, jsonAggregation);
    } else {
      convertFieldByNature(dtoVariable, jsonAggregation);
    }
  }

  /**
   * Convert variable query to field aggregation of the specified type (if applicable).
   *
   * @param dtoVariable
   * @param jsonAggregation
   * @throws JSONException
   * @throws UnsupportedOperationException
   */
  private void convertFieldByType(Search.VariableTermDto dtoVariable, JSONObject jsonAggregation)
      throws JSONException, UnsupportedOperationException {
    String variable = dtoVariable.getVariable();
    JSONObject jsonField = new JSONObject();
    jsonField.put("field", variableFieldName(variable));

    switch(dtoVariable.getType()) {
      case MISSING:
        jsonAggregation.put("missing", jsonField);
        break;
      case CARDINALITY:
        jsonAggregation.put("cardinality", jsonField);
        break;
      case TERMS:
        jsonField.put("size", termsFacetSize);
        jsonAggregation.put("terms", jsonField);
        break;
      case STATS:
        if(getVariableNature(variable) != VariableNature.CONTINUOUS)
          throw new IllegalArgumentException(
              "Statistics aggregation is only applicable to numeric continuous variables");
        jsonAggregation.put("extended_stats", jsonField);
        break;
      case PERCENTILES:
        if(getVariableNature(variable) != VariableNature.CONTINUOUS)
          throw new IllegalArgumentException(
              "Percentiles aggregation is only applicable to numeric continuous variables");
        jsonAggregation.put("percentiles", jsonField);
        break;
    }
  }

  /**
   * Convert field query to default field aggregation according to variable nature.
   *
   * @param dtoVariable
   * @param jsonAggregation
   * @throws JSONException
   * @throws UnsupportedOperationException
   */
  private void convertFieldByNature(Search.VariableTermDto dtoVariable, JSONObject jsonAggregation)
      throws JSONException, UnsupportedOperationException {
    String variable = dtoVariable.getVariable();
    JSONObject jsonField = new JSONObject();
    jsonField.put("field", variableFieldName(variable));

    switch(getVariableNature(variable)) {

      case CONTINUOUS:
        jsonAggregation.put("extended_stats", jsonField);
        break;

      case CATEGORICAL:
        // we want all categories frequencies: as we do not know the variable description at this point,
        // set a maximum size to term facets request (0 means maximum)
        // http://www.elasticsearch.org/guide/en/elasticsearch/reference/current/search-aggregations-bucket-terms-aggregation.html
        jsonField.put("size", 0);
        jsonAggregation.put("terms", jsonField);
        break;

      default:
        jsonField.put("size", termsFacetSize);
        jsonAggregation.put("terms", jsonField);
        break;
    }
  }

  private JSONObject convertFilterType(Search.FilterDto dtoFilter) throws JSONException {
    JSONObject jsonFilter = new JSONObject();

    String variable = dtoFilter.getVariable();

    if(dtoFilter.hasExtension(Search.InTermDto.terms)) {
      convertTermFilter(dtoFilter.getExtension(Search.InTermDto.terms), jsonFilter, variable);
    } else if(dtoFilter.hasExtension(Search.RangeTermDto.range)) {
      convertRangeFilter(dtoFilter.getExtension(Search.RangeTermDto.range), jsonFilter, variable);
    } else {
      convertExistFilter(jsonFilter, variable);
    }

    if(dtoFilter.hasNot() && dtoFilter.getNot()) {
      jsonFilter = new JSONObject().put("not", jsonFilter);
    }

    return jsonFilter;
  }

  private void convertRangeFilter(Search.RangeTermDto dtoRange, JSONObject jsonFilter, String variable)
      throws JSONException {

    JSONObject jsonRange = new JSONObject();

    if(dtoRange.hasFrom()) {
      jsonRange.put("from", dtoRange.getFrom());
    }

    if(dtoRange.hasIncludeLower()) {
      jsonRange.put("include_lower", dtoRange.getIncludeLower());
    }

    if(dtoRange.hasTo()) {
      jsonRange.put("to", dtoRange.getTo());
    }

    if(dtoRange.hasIncludeUpper()) {
      jsonRange.put("include_upper", dtoRange.getIncludeUpper());
    }

    jsonFilter.put("numeric_range", new JSONObject().put(variableFieldName(variable), jsonRange));
  }

  private void convertExistFilter(JSONObject jsonFilter, String variable) throws JSONException {
    jsonFilter.put("exists", new JSONObject().put("field", variableFieldName(variable)));
  }

  private void convertTermFilter(Search.InTermDto dtoTerms, JSONObject jsonFilter, String variable)
      throws JSONException {

    // see if we're dealing a 'term' or 'terms' elastic search facet type
    List<String> values = dtoTerms.getValuesList();

    if(values.size() == 1) {
      jsonFilter.put("term", new JSONObject().put(variableFieldName(variable), values.get(0)));
    } else {
      jsonFilter.put("terms", new JSONObject().put(variableFieldName(variable), values));
    }
  }

  private String variableFieldName(String variable) {
    try {
      return valueTableIndexManagers.values().iterator().next().getIndexFieldName(variable);
    } catch (NoSuchVariableException e) {
      MagmaEngineVariableResolver resolver = MagmaEngineVariableResolver.valueOf(variable);
      ValueTableIndexManager manager = getValueTableIndexManager(resolver);
      if (manager == null) throw e;
      return manager.getIndexFieldName(resolver.getVariableName());
    }
  }

  private String getQueryString() {
    return Joiner.on(" OR ").join(valueTableIndexManagers.values().stream().map(ValueTableIndexManager::getQuery).collect(Collectors.toList()));
  }

  private VariableNature getVariableNature(String variable) {
    try {
      return valueTableIndexManagers.values().iterator().next().getVariableNature(variable);
    } catch (NoSuchVariableException e) {
      MagmaEngineVariableResolver resolver = MagmaEngineVariableResolver.valueOf(variable);
      ValueTableIndexManager manager = getValueTableIndexManager(resolver);
      if (manager == null) throw e;
      return manager.getVariableNature(resolver.getVariableName());
    }
  }

  private ValueTableIndexManager getValueTableIndexManager(MagmaEngineVariableResolver resolver) {
    if (!resolver.hasDatasourceName() || !resolver.hasTableName()) return null;
    String ref = resolver.getDatasourceName() + "." + resolver.getTableName();
    if (!valueTableIndexManagers.containsKey(ref)) {
      valueTableIndexManagers.put(ref, valueTableIndexManagers.values().iterator().next().copy(resolver.getDatasourceName(), resolver.getTableName()));
    }
    return valueTableIndexManagers.get(ref);
  }

}