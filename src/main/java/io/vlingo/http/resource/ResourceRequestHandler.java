// Copyright © 2012-2018 Vaughn Vernon. All rights reserved.
//
// This Source Code Form is subject to the terms of the
// Mozilla Public License, v. 2.0. If a copy of the MPL
// was not distributed with this file, You can obtain
// one at https://mozilla.org/MPL/2.0/.

package io.vlingo.http.resource;

import java.util.function.Consumer;

import io.vlingo.http.Context;
import io.vlingo.http.resource.Action.MappedParameters;

public interface ResourceRequestHandler {
  @SuppressWarnings("rawtypes")
  void handleFor(final Context context, final Consumer consumer);

  void handleFor(final Context context, final MappedParameters mappedParameters, final RequestHandler handler);
}
