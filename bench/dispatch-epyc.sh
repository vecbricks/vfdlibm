#!/bin/bash
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
#
# bench/dispatch-bench.sh on the AMD EPYC 7763 host, the only benchmark host before the other
# two; kept so that the calls written before them still work unchanged:
#
#   bench/dispatch-epyc.sh <ref> [benchmark regex] [extra JVM options]

exec "$(dirname "$0")/dispatch-bench.sh" --host epyc7763 -- "$@"
