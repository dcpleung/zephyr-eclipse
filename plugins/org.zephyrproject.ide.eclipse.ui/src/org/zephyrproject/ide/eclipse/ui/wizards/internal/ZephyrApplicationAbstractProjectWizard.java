/*
 * Copyright (c) 2019 Intel Corporation
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.zephyrproject.ide.eclipse.ui.wizards.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.model.CModelException;
import org.eclipse.cdt.core.model.CoreModel;
import org.eclipse.cdt.core.model.ICProject;
import org.eclipse.cdt.core.model.IPathEntry;
import org.eclipse.core.resources.FileInfoMatcherDescription;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceFilterDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ResourceLocator;
import org.eclipse.jface.wizard.IWizardContainer;
import org.eclipse.tools.templates.core.IGenerator;
import org.eclipse.tools.templates.ui.TemplateWizard;
import org.zephyrproject.ide.eclipse.core.ZephyrApplicationNewProjectGenerator;
import org.zephyrproject.ide.eclipse.core.ZephyrPlugin;
import org.zephyrproject.ide.eclipse.core.ZephyrStrings;
import org.zephyrproject.ide.eclipse.core.preferences.ZephyrProjectPreferences.ZephyrBase;
import org.zephyrproject.ide.eclipse.ui.ZephyrUIPlugin;
import org.zephyrproject.ide.eclipse.ui.wizards.internal.ZephyrApplicationBoardWizardPage;
import org.zephyrproject.ide.eclipse.ui.wizards.internal.ZephyrApplicationMainWizardPage;
import org.zephyrproject.ide.eclipse.ui.wizards.internal.ZephyrApplicationToolchainWizardPage;

public abstract class ZephyrApplicationAbstractProjectWizard
		extends TemplateWizard {

	protected ZephyrApplicationMainWizardPage mainPage;

	protected ZephyrApplicationToolchainWizardPage toolchainPage;

	protected ZephyrApplicationBoardWizardPage boardPage;

	protected static final String WIZARD_NAME =
			ZephyrStrings.ZEPHYR_APPLICATION_PROJECT + " Wizard";

	protected ZephyrApplicationNewProjectGenerator generator;

	protected String templateFile;

	public ZephyrApplicationAbstractProjectWizard(String templateFile) {
		super();

		this.templateFile = templateFile;

		setDialogSettings(ZephyrPlugin.getDefault().getDialogSettings());
		setNeedsProgressMonitor(true);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * org.eclipse.jface.wizard.Wizard#setContainer(org.eclipse.jface.wizard.
	 * IWizardContainer)
	 */
	@Override
	public void setContainer(IWizardContainer wizardContainer) {
		super.setContainer(wizardContainer);
		setWindowTitle(ZephyrStrings.ZEPHYR_APPLICATION);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.eclipse.jface.wizard.Wizard#addPages()
	 */
	@Override
	public void addPages() {
		mainPage = new ZephyrApplicationMainWizardPage(WIZARD_NAME);
		mainPage.setTitle(ZephyrStrings.ZEPHYR_APPLICATION_PROJECT);
		mainPage.setDescription(
				"Create a new " + ZephyrStrings.ZEPHYR_APPLICATION);
		addPage(mainPage);

		toolchainPage = new ZephyrApplicationToolchainWizardPage(WIZARD_NAME);
		toolchainPage.setTitle(ZephyrStrings.ZEPHYR_APPLICATION_PROJECT
				+ " - Toolchain Selection");
		toolchainPage.setDescription(
				"Specify the Toolchain to Build this Application");
		addPage(toolchainPage);

		boardPage = new ZephyrApplicationBoardWizardPage(WIZARD_NAME, mainPage);
		boardPage.setTitle(ZephyrStrings.ZEPHYR_APPLICATION_PROJECT
				+ " - Target Board Configuration");
		boardPage.setDescription("Specify the target board configuration");
		addPage(boardPage);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.eclipse.tools.templates.ui.TemplateWizard#getGenerator()
	 */
	@Override
	protected IGenerator getGenerator() {
		if (generator != null) {
			return generator;
		}

		generator = new ZephyrApplicationNewProjectGenerator(templateFile);
		generator.setProjectName(mainPage.getProjectName());
		generator.setCMakeGenerator(mainPage.getCMakeGenerator());
		generator.setSourceDirectory(mainPage.getSourceDirectory());
		if (!mainPage.useDefaults()) {
			generator.setLocationURI(mainPage.getLocationURI());
		}
		return generator;
	}

	/**
	 * Show an error dialog and delete the project from workspace.
	 *
	 * Project must be created in the workspace before configuration of
	 * the project can take place. The configuration phase may not complete
	 * entirely, so this is to avoid creating an incomplete/invalid project in
	 * the workspace.
	 *
	 * @param msg The message to be displayed in the dialog.
	 * @param t Throwable that can be displayed in the dialog.
	 */
	private void showErrorDialogAndDeleteProject(String msg, Throwable t) {
		Status status = new Status(IStatus.ERROR, ZephyrPlugin.PLUGIN_ID, 0,
				t.getLocalizedMessage(), t);
		ErrorDialog.openError(getShell(), "Error", msg, status);

		try {
			mainPage.getProjectHandle().delete(false, false, null);
		} catch (CoreException ce) {
			/* ignore */
		}
	}

	/**
	 * Perform actions associated with finishing the wizard.
	 */
	@Override
	public boolean performFinish() {
		/*
		 * TemplateWizard.performFinish() always return true, but would throw
		 * RuntimeException.
		 */
		try {
			super.performFinish();
		} catch (RuntimeException e) {
			showErrorDialogAndDeleteProject("Cannot create project files", e);
			return false;
		}

		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		IProject project =
				workspace.getRoot().getProject(mainPage.getProjectName());
		ICProject cProj =
				CCorePlugin.getDefault().getCoreModel().create(project);

		List<IPathEntry> entries = new ArrayList<>();
		try {
			entries = new ArrayList<>(Arrays.asList(cProj.getRawPathEntries()));
		} catch (CModelException e) {
			showErrorDialogAndDeleteProject("Error getting paths from CDT", e);
			return false;
		}

		/*
		 * The project root path is designated as both source and output by
		 * default. Remove them to avoid confusing the indexer.
		 */
		Iterator<IPathEntry> iter = entries.iterator();
		while (iter.hasNext()) {
			IPathEntry path = iter.next();

			if (((path.getEntryKind() == IPathEntry.CDT_SOURCE)
					|| (path.getEntryKind() == IPathEntry.CDT_OUTPUT))
					&& (path.getPath().equals(project.getFullPath()))) {
				iter.remove();
			}
		}

		/*
		 * Create the build directory, and let CDT know where the build (output)
		 * directory is, excluding CMake directories.
		 */
		IFolder buildFolder = project.getFolder(mainPage.getBuildDirectory());
		if (!buildFolder.exists()) {
			try {
				buildFolder.create(IResource.FORCE | IResource.DERIVED, true,
						new NullProgressMonitor());
			} catch (CoreException e) {
				showErrorDialogAndDeleteProject("Cannot create build directory",
						e);
				return false;
			}
		}

		entries.add(CoreModel.newOutputEntry(buildFolder.getFullPath(),
				new IPath[] {
					new Path("**/CMakeFiles/**") //$NON-NLS-1$
				}));

		/*
		 * Create a link to ZEPHYR_BASE so the indexer can also index the Zephyr
		 * core code.
		 */
		IFolder zBase = project.getFolder(ZephyrBase.ZEPHYR_BASE); // $NON-NLS-1$
		String zBaseLoc = mainPage.getZephyrBaseLocation();
		IPath zBaseLink = new Path(zBaseLoc);

		if (zBase.exists() && zBase.isLinked()) {
			/*
			 * The project itself might be deleted from workspace metadata, but
			 * project files still exist on storage.
			 */
			try {
				zBase.delete(false, new NullProgressMonitor());
			} catch (CoreException e) {
				showErrorDialogAndDeleteProject(String.format(
						"Cannot create project due to pre-existing linked resource '%s'",
						zBase.getFullPath()), e);
				return false;
			}
		}

		IStatus zBaseLinkValid =
				workspace.validateLinkLocation(zBase, zBaseLink);
		if (zBaseLinkValid.isOK() || zBaseLinkValid.matches(IStatus.WARNING)) {
			/*
			 * WARNING means the linked resource also appears in another
			 * project, so not exactly an issue.
			 */
			try {
				/* Filter out "sanity-out" folder from linked resource */
				FileInfoMatcherDescription sanityOutFilter =
						new FileInfoMatcherDescription(
								"org.eclipse.ui.ide.multiFilter",
								"1.0-name-matches-false-false-sanity-out");
				zBase.createFilter(
						IResourceFilterDescription.EXCLUDE_ALL
								| IResourceFilterDescription.FOLDERS,
						sanityOutFilter, IResource.NONE, null);

				/* Filter out "build" folder from linked resource */
				FileInfoMatcherDescription buildFilter =
						new FileInfoMatcherDescription(
								"org.eclipse.ui.ide.multiFilter",
								"1.0-name-matches-false-false-build");
				zBase.createFilter(
						IResourceFilterDescription.EXCLUDE_ALL
								| IResourceFilterDescription.FOLDERS,
						buildFilter, IResource.NONE, null);

				/* Create link in project */
				zBase.createLink(zBaseLink, IResource.BACKGROUND_REFRESH, null);
			} catch (CoreException e) {
				showErrorDialogAndDeleteProject(
						String.format("Error creating linked resource to %s",
								ZephyrBase.DIRECTORY_DESCRIPTION),
						e);
				return false;
			}
		} else {
			RuntimeException e = new RuntimeException("Link not valid");
			showErrorDialogAndDeleteProject(
					String.format("Error creating linked resource to %s",
							ZephyrBase.DIRECTORY_DESCRIPTION),
					e);
			return false;
		}

		/*
		 * Also need to tell CDT ZEPHYR_BASE is source so it will index the
		 * source inside, for code completion and references/declarations
		 * searching. However, exclude everything first so it would not
		 * index any files before CMake is run.
		 */
		IPath[] exclusion = new Path[] {
			new Path("*"),
		};

		entries.add(CoreModel.newSourceEntry(zBase.getFullPath(), exclusion));

		try {
			cProj.setRawPathEntries(entries.toArray(new IPathEntry[0]), null);
		} catch (CModelException e) {
			showErrorDialogAndDeleteProject("Error setting paths to CDT", e);
			return false;
		}

		try {
			mainPage.performFinish(project);
			toolchainPage.performFinish(project);
			boardPage.performFinish(project);
		} catch (IOException e) {
			showErrorDialogAndDeleteProject("Cannot save project settings", e);
			return false;
		}

		if (generator != null) {
			generator.notifyProjectCreationComplete(cProj);
		}

		return true;
	}

	/**
	 * This sets the icon for the wizard page.
	 */
	@Override
	protected void initializeDefaultPageImageDescriptor() {
		ImageDescriptor desc = ResourceLocator.imageDescriptorFromBundle(
				ZephyrUIPlugin.PLUGIN_ID, "icons/wizard.png").get(); //$NON-NLS-1$
		setDefaultPageImageDescriptor(desc);
	}

}
