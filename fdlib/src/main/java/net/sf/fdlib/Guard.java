/*
 * Copyright © 2016 Alexander Rvachev
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
package net.sf.fdlib;

import java.io.Closeable;

/**
 * Virtually all modern operation systems use explicit memory and resource management. In order
 * to reconcile it's GC-based approach to releasing memory with underlying platform, Java creators
 * have designed it with a builtin workaround — finalization. Unfortunately, finalization has
 * a number of well known downsides: it relies on executing various human-written finalizer code
 * (bug-prone!), requires a second pass of garbage collector to ensure, that finalizers
 * didn't re-create a permanent reference to object, and, most importantly, it is either redundant
 * or insufficiently flexible in majority of cases.
 *
 * <p/>
 *
 * Look at the following code:
 *
 * <pre>
 *     void sayHello(Binder binder) throws RemoteException, IOException {
 *         Parcel in = Parcel.obtain();
 *         Parcel out = Parcel.obtain();
 *
 *         binder.transact(Binder.FIRST_CALL_TRANSACTION, in, out, 0);
 *
 *         try (ParcelFileDescriptor descriptor = out.readFileDescriptor();
 *              PrintWriter pw = new PrintWriter(new FileOutputStream(descriptor.getFileDescriptor()))) {
 *             pw.write("Hello world");
 *         } finally {
 *             in.recycle();
 *             out.recycle();
 *         }
 *     }
 * </pre>
 *
 * How many finalizers have to be invoked before fully disposing of objects, created in the listing above?
 * How many <strong>need</strong> to be invoked, considering that the method deterministically disposes
 * of everything using try-with-resources block? Even code, that can not be wrapped in such block,
 * does not usually need all finalizers to be invoked — one managed resource wraps another and
 * eventually only the topmost one would require to be explicitly guarded against accidental leaks.
 * But the builtin finalizer mechanism does not allow to dynamically turn off finalization for
 * specific object. Nor does it allow to replace or adjust the finalization strategy built into
 * a class. This ultimately makes finalization either hindrance for library user or obstacle in the
 * way of clean and flexible library design.
 *
 * <p/>
 *
 * For a concrete solution see {@link CloseableGuard}
 */
public interface Guard extends Closeable {
    @Override
    void close();
}
