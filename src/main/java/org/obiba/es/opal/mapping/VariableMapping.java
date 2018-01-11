/*
 * Copyright (c) 2018 OBiBa. All rights reserved.
 *
 * This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.obiba.es.opal.mapping;

import org.elasticsearch.common.xcontent.XContentBuilder;
import org.obiba.magma.Variable;

import java.io.IOException;
import java.util.Map;

public interface VariableMapping {

  void map(Variable variable, XContentBuilder builder) throws IOException;

  void map(Variable variable, Map<String, Object> mapping);

}
