/*******************************************************************************
 * Copyright (c) 2016, 2019 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.aspectj.org.eclipse.jdt.internal.core.builder;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipFile;

import org.eclipse.core.runtime.IPath;
import org.aspectj.org.eclipse.jdt.core.compiler.CharOperation;
import org.aspectj.org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader;
import org.aspectj.org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException;
import org.aspectj.org.eclipse.jdt.internal.compiler.classfmt.ExternalAnnotationDecorator;
import org.aspectj.org.eclipse.jdt.internal.compiler.env.AccessRuleSet;
import org.aspectj.org.eclipse.jdt.internal.compiler.env.IBinaryType;
import org.aspectj.org.eclipse.jdt.internal.compiler.env.IModule;
import org.aspectj.org.eclipse.jdt.internal.compiler.env.IModule.IModuleReference;
import org.aspectj.org.eclipse.jdt.internal.compiler.env.IMultiModuleEntry;
import org.aspectj.org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;
import org.aspectj.org.eclipse.jdt.internal.compiler.util.JRTUtil;
import org.aspectj.org.eclipse.jdt.internal.compiler.util.SimpleSet;
import org.aspectj.org.eclipse.jdt.internal.compiler.util.SuffixConstants;
import org.aspectj.org.eclipse.jdt.internal.core.JavaProject;

public class ClasspathJrt extends ClasspathLocation implements IMultiModuleEntry {

//private HashMap<String, SimpleSet> packagesInModule = null;
protected static HashMap<String, HashMap<String, SimpleSet>> PackageCache = new HashMap<>();
protected static HashMap<String, Set<IModule>> ModulesCache = new HashMap<>();
String externalAnnotationPath;
protected ZipFile annotationZipFile;
String zipFilename; // keep for equals
AccessRuleSet accessRuleSet;

static final Set<String> NO_LIMIT_MODULES = new HashSet<>();

/*
 * Only for use from ClasspathJrtWithOlderRelease
 */
protected ClasspathJrt() {
}
public ClasspathJrt(String zipFilename, AccessRuleSet accessRuleSet, IPath externalAnnotationPath) {
	this.zipFilename = zipFilename;
	this.accessRuleSet = accessRuleSet;
	if (externalAnnotationPath != null)
		this.externalAnnotationPath = externalAnnotationPath.toString();
	loadModules(this);
}
/**
 * Calculate and cache the package list available in the zipFile.
 * @param jrt The ClasspathJar to use
 * @return A SimpleSet with the all the package names in the zipFile.
 */
static HashMap<String, SimpleSet> findPackagesInModules(final ClasspathJrt jrt) {
	String zipFileName = jrt.zipFilename;
	HashMap<String, SimpleSet> cache = PackageCache.get(jrt.getKey());
	if (cache != null) {
		return cache;
	}
	final HashMap<String, SimpleSet> packagesInModule = new HashMap<>();
	PackageCache.put(zipFileName, packagesInModule);
	try {
		final File imageFile = new File(zipFileName);
		org.aspectj.org.eclipse.jdt.internal.compiler.util.JRTUtil.walkModuleImage(imageFile,
				new org.aspectj.org.eclipse.jdt.internal.compiler.util.JRTUtil.JrtFileVisitor<Path>() {
			SimpleSet packageSet = null;
			@Override
			public FileVisitResult visitPackage(Path dir, Path mod, BasicFileAttributes attrs) throws IOException {
				ClasspathJar.addToPackageSet(this.packageSet, dir.toString(), true);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, Path mod, BasicFileAttributes attrs) throws IOException {
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitModule(Path path, String name) throws IOException {
				try {
					jrt.acceptModule(JRTUtil.getClassfileContent(imageFile, IModule.MODULE_INFO_CLASS, name));
				} catch (ClassFormatException e) {
					e.printStackTrace();
				}
				this.packageSet = new SimpleSet(41);
				this.packageSet.add(""); //$NON-NLS-1$
				if (name.endsWith("/")) { //$NON-NLS-1$
					name = name.substring(0, name.length() - 1);
				}
				packagesInModule.put(name, this.packageSet);
				return FileVisitResult.CONTINUE;
			}
		}, JRTUtil.NOTIFY_PACKAGES | JRTUtil.NOTIFY_MODULES);
	} catch (IOException e) {
		// TODO: Java 9 Should report better
	}
	return packagesInModule;
}

public static void loadModules(final ClasspathJrt jrt) {
	Set<IModule> cache = ModulesCache.get(jrt.getKey());

	if (cache == null) {
		try {
			final File imageFile = new File(jrt.zipFilename);
			org.aspectj.org.eclipse.jdt.internal.compiler.util.JRTUtil.walkModuleImage(imageFile,
					new org.aspectj.org.eclipse.jdt.internal.compiler.util.JRTUtil.JrtFileVisitor<Path>() {
				SimpleSet packageSet = null;

				@Override
				public FileVisitResult visitPackage(Path dir, Path mod, BasicFileAttributes attrs)
						throws IOException {
					ClasspathJar.addToPackageSet(this.packageSet, dir.toString(), true);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, Path mod, BasicFileAttributes attrs)
						throws IOException {
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitModule(Path path, String name) throws IOException {
					try {
						jrt.acceptModule(JRTUtil.getClassfileContent(imageFile, IModule.MODULE_INFO_CLASS, name));
					} catch (ClassFormatException e) {
						e.printStackTrace();
					}
					return FileVisitResult.SKIP_SUBTREE;
				}
			}, JRTUtil.NOTIFY_MODULES);
		} catch (IOException e) {
			// TODO: Java 9 Should report better
		}
	} else {
//		for (IModuleDeclaration iModule : cache) {
//			jimage.env.acceptModule(iModule, jimage);
//		}
	}
}
protected String getKey() {
	return this.zipFilename;
}
void acceptModule(byte[] content) {
	if (content == null)
		return;
	ClassFileReader reader = null;
	try {
		reader = new ClassFileReader(content, IModule.MODULE_INFO_CLASS.toCharArray());
	} catch (ClassFormatException e) {
		e.printStackTrace();
	}
	if (reader != null) {
		String key = getKey();
		IModule moduleDecl = reader.getModuleDeclaration();
		if (moduleDecl != null) {
			Set<IModule> cache = ModulesCache.get(key);
			if (cache == null) {
				ModulesCache.put(key, cache = new HashSet<IModule>());
			}
			cache.add(moduleDecl);
		}
	}
}
@Override
public void cleanup() {
	if (this.annotationZipFile != null) {
		try {
			this.annotationZipFile.close();
		} catch(IOException e) { // ignore it
		}
		this.annotationZipFile = null;
	}
}

@Override
public boolean equals(Object o) {
	if (this == o) return true;
	if (!(o instanceof ClasspathJrt)) return false;
	ClasspathJrt jar = (ClasspathJrt) o;
	if (this.accessRuleSet != jar.accessRuleSet)
		if (this.accessRuleSet == null || !this.accessRuleSet.equals(jar.accessRuleSet))
			return false;
	return this.zipFilename.endsWith(jar.zipFilename) && areAllModuleOptionsEqual(jar);
}

@Override
public NameEnvironmentAnswer findClass(String binaryFileName, String qualifiedPackageName, String moduleName, String qualifiedBinaryFileName,
										boolean asBinaryOnly, Predicate<String> moduleNameFilter) {
	if (!isPackage(qualifiedPackageName, moduleName)) return null; // most common case

	try {
		String fileNameWithoutExtension = qualifiedBinaryFileName.substring(0, qualifiedBinaryFileName.length() - SuffixConstants.SUFFIX_CLASS.length);
		IBinaryType reader = ClassFileReader.readFromModule(new File(this.zipFilename), moduleName, qualifiedBinaryFileName, moduleNameFilter);
		return createAnswer(fileNameWithoutExtension, reader);
	} catch (ClassFormatException | IOException e) { // treat as if class file is missing
	}
	return null;
}
protected NameEnvironmentAnswer createAnswer(String fileNameWithoutExtension, IBinaryType reader) {
	if (reader != null) {
		if (this.externalAnnotationPath != null) {
			try {
				if (this.annotationZipFile == null) {
					this.annotationZipFile = ExternalAnnotationDecorator.getAnnotationZipFile(this.externalAnnotationPath, null);
				}
				reader = ExternalAnnotationDecorator.create(reader, this.externalAnnotationPath, fileNameWithoutExtension, this.annotationZipFile);
			} catch (IOException e) {
				// don't let error on annotations fail class reading
			}
		}
		if (this.accessRuleSet == null)
			return new NameEnvironmentAnswer(reader, null, reader.getModule());
		return new NameEnvironmentAnswer(reader,
				this.accessRuleSet.getViolatedRestriction(fileNameWithoutExtension.toCharArray()),
				reader.getModule());
	}
	return null;
}

@Override
public IPath getProjectRelativePath() {
	return null;
}

@Override
public int hashCode() {
	return this.zipFilename == null ? super.hashCode() : this.zipFilename.hashCode();
}
@Override
public char[][] getModulesDeclaringPackage(String qualifiedPackageName, String moduleName) {
	List<String> moduleNames = JRTUtil.getModulesDeclaringPackage(new File(this.zipFilename), qualifiedPackageName, moduleName);
	return CharOperation.toCharArrays(moduleNames);
}
@Override
public boolean hasCompilationUnit(String qualifiedPackageName, String moduleName) {
	return JRTUtil.hasCompilationUnit(new File(this.zipFilename), qualifiedPackageName, moduleName);
}
@Override
public boolean isPackage(String qualifiedPackageName, String moduleName) {
	return JRTUtil.getModulesDeclaringPackage(new File(this.zipFilename), qualifiedPackageName, moduleName) != null;
}

@Override
public String toString() {
	String start = "Classpath jrt file " + this.zipFilename; //$NON-NLS-1$
	return start;
}

@Override
public String debugPathString() {
	return this.zipFilename;
}
@Override
public NameEnvironmentAnswer findClass(char[] typeName, String qualifiedPackageName, String moduleName, String qualifiedBinaryFileName,
		boolean asBinaryOnly, Predicate<String> moduleNameFilter) {
	String fileName = new String(typeName);
	return findClass(fileName, qualifiedPackageName, moduleName, qualifiedBinaryFileName, asBinaryOnly, moduleNameFilter);
}
@Override
public boolean hasModule() {
	return true;
}
@Override
public IModule getModule(char[] moduleName) {
	Set<IModule> modules = ModulesCache.get(getKey());
	if (modules != null) {
		for (IModule mod : modules) {
			if (CharOperation.equals(mod.name(), moduleName))
					return mod;
		}
	}
	return null;
}
@Override
public Collection<String> getModuleNames(Collection<String> limitModules) {
	HashMap<String, SimpleSet> cache = findPackagesInModules(this);
	if (cache != null)
		return selectModules(cache.keySet(), limitModules);
	return Collections.emptyList();
}

protected Collection<String> selectModules(Set<String> keySet, Collection<String> limitModules) {
	Collection<String> rootModules;
	if (limitModules == NO_LIMIT_MODULES) {
		rootModules = new HashSet<>(keySet);
	} else if (limitModules != null) {
		Set<String> result = new HashSet<>(keySet);
		result.retainAll(limitModules);
		rootModules = result;
	} else {
		rootModules = JavaProject.internalDefaultRootModules(keySet, s -> s, m -> getModule(m.toCharArray()));
	}
	Set<String> allModules = new HashSet<>(rootModules);
	for (String mod : rootModules)
		addRequired(mod, allModules);
	return allModules;
}

protected void addRequired(String mod, Set<String> allModules) {
	IModule iMod = getModule(mod.toCharArray());
	if(iMod == null) {
		return;
	}
	for (IModuleReference requiredRef : iMod.requires()) {
		IModule reqMod = getModule(requiredRef.name());
		if (reqMod != null) {
			String reqModName = String.valueOf(reqMod.name());
			if (allModules.add(reqModName))
				addRequired(reqModName, allModules);
		}
	}
}
@Override
public NameEnvironmentAnswer findClass(String typeName, String qualifiedPackageName, String moduleName, String qualifiedBinaryFileName) {
	//
	return findClass(typeName, qualifiedPackageName, moduleName, qualifiedBinaryFileName, false, null);
}
/** TEST ONLY */
public static void resetCaches() {
	PackageCache.clear();
	ModulesCache.clear();
}
}
