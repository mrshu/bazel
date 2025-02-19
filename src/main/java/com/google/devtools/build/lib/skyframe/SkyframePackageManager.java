// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.io.InconsistentFilesystemException;
import com.google.devtools.build.lib.packages.CachingPackageLocator;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.PackageManager;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.pkgcache.QueryTransitivePackagePreloader;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor.SkyframePackageLoader;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.SyscallCache;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

class SkyframePackageManager implements PackageManager, CachingPackageLocator {
  private final SkyframePackageLoader packageLoader;
  private final QueryTransitivePackagePreloader transitiveLoader;
  private final Supplier<SyscallCache> syscallCache;
  private final Supplier<PathPackageLocator> pkgLocator;
  private final AtomicInteger numPackagesLoaded;

  public SkyframePackageManager(
      SkyframePackageLoader packageLoader,
      QueryTransitivePackagePreloader transitiveLoader,
      Supplier<SyscallCache> syscallCache,
      Supplier<PathPackageLocator> pkgLocator,
      AtomicInteger numPackagesLoaded) {
    this.packageLoader = packageLoader;
    this.transitiveLoader = transitiveLoader;
    this.pkgLocator = pkgLocator;
    this.syscallCache = syscallCache;
    this.numPackagesLoaded = numPackagesLoaded;
  }

  @ThreadSafe
  @Override
  public Package getPackage(ExtendedEventHandler eventHandler, PackageIdentifier packageIdentifier)
      throws NoSuchPackageException, InterruptedException {
    return packageLoader.getPackage(eventHandler, packageIdentifier);
  }

  @Override
  public Target getTarget(ExtendedEventHandler eventHandler, Label label)
      throws NoSuchPackageException, NoSuchTargetException, InterruptedException {
    return Preconditions.checkNotNull(getPackage(eventHandler, label.getPackageIdentifier()), label)
        .getTarget(label.getName());
  }

  @Override
  public PackageManagerStatistics getAndClearStatistics() {
    int packagesLoaded = numPackagesLoaded.getAndSet(0);
    return () -> packagesLoaded;
  }

  @Override
  public boolean isPackage(ExtendedEventHandler eventHandler, PackageIdentifier packageName)
      throws InconsistentFilesystemException, InterruptedException {
    return getBuildFileForPackage(packageName) != null;
  }

  @Override
  public void dump(PrintStream printStream) {
    packageLoader.dumpPackages(printStream);
  }

  @ThreadSafe
  @Override
  public Path getBuildFileForPackage(PackageIdentifier packageName) {
    // Note that this method needs to be thread-safe, as it is currently used concurrently by
    // legacy blaze code.
    if (packageLoader.isPackageDeleted(packageName)) {
      return null;
    }
    // TODO(bazel-team): Use a PackageLookupValue here [skyframe-loading]
    // TODO(bazel-team): The implementation in PackageCache also checks for duplicate packages, see
    // BuildFileCache#getBuildFile [skyframe-loading]
    return pkgLocator.get().getPackageBuildFileNullable(packageName, syscallCache.get());
  }

  @Override
  public String getBaseNameForLoadedPackage(PackageIdentifier packageName) {
    PackageLookupValue pkgLookupValue =
        checkNotNull(
            packageLoader.getPackageLookupValue(packageName),
            "Package should already have been visited: %s",
            packageName);
    checkState(
        pkgLookupValue.packageExists(), "Package must exist: %s %s", packageName, pkgLookupValue);
    return pkgLookupValue.getBuildFileName().getFilenameFragment().getBaseName();
  }

  @Override
  public PathPackageLocator getPackagePath() {
    return pkgLocator.get();
  }

  @Override
  public QueryTransitivePackagePreloader transitiveLoader() {
    return transitiveLoader;
  }
}
