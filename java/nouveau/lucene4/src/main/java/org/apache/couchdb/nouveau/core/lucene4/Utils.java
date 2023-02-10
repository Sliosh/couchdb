//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.apache.couchdb.nouveau.core.lucene4;

import org.apache.lucene.index.Term;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;
import org.apache.lucene.util.Version;

class Utils {

    static final Version LUCENE_VERSION = Version.LUCENE_46;

    static Term doubleToTerm(String field, Double value) {
        var bytesRef = new BytesRef();
        var asLong = NumericUtils.doubleToSortableLong(value);
        NumericUtils.longToPrefixCoded(asLong, 0, bytesRef);
        return new Term(field, bytesRef);
    }

}
