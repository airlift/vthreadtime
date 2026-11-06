/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/// Hook classes that are defined into the bootstrap class loader at runtime.
///
/// Bytecode injected into `java.lang.VirtualThread` calls [VirtualThreadHooks] on every
/// mount and unmount. Code in `java.base` can only link against classes visible to the
/// bootstrap loader, so the native library defines these two classes there from the `.class`
/// resources shipped in the jar, instead of letting the module's class loader load them.
///
/// Consequently the classes in this package must reference nothing outside `java.base` and
/// this package, must not be exported by the module, and must never be referenced statically
/// from any other class of the module: the application side reaches the boot-loaded copies by
/// name and calls them through method handles.
package io.airlift.vthreadtime.hook;
