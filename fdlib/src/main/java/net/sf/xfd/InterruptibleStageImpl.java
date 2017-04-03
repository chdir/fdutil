/*
 * Copyright © 2017 Alexander Rvachev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.sf.xfd;

public final class InterruptibleStageImpl extends InterruptibleStage implements Runnable {
    private static final Cache cache = new Cache();

    final Interruption i = Interruption.newInstance();
    final int tid = Interruption.myTid();

    public void begin() {
        super.begin(this);
    }

    @Override
    public void run() {
        i.interrupt(tid);
    }

    public static InterruptibleStageImpl get() {
        return cache.get();
    }

    private static final class Cache extends ThreadLocal<InterruptibleStageImpl> {
        @Override
        protected InterruptibleStageImpl initialValue() {
            return new InterruptibleStageImpl();
        }
    }
}
