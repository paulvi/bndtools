/*******************************************************************************
 * Copyright (c) 2010 Neil Bartlett.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Neil Bartlett - initial API and implementation
 *******************************************************************************/
package bndtools.wizards.bndfile;

import bndtools.wizards.project.ClasspathEditorWizardPage;



public class WrappingBndFileWizard extends EmptyBndFileWizard {

	private ClasspathEditorWizardPage classpathPage;
	
	@Override
	public void addPages() {
		mainPage = new NewWrappingBndFileWizardPage("mainPage", selection); //$NON-NLS-1$
		mainPage.setFileExtension("bnd"); //$NON-NLS-1$
		mainPage.setAllowExistingResources(false);

		addPage(mainPage);
		
		classpathPage = new ClasspathEditorWizardPage("classpath", mainPage); //$NON-NLS-1$
		addPage(classpathPage);
	}

	@Override
	public boolean performFinish() {
		((NewWrappingBndFileWizardPage) mainPage).setPaths(classpathPage.getPaths());
		
		return super.performFinish();
	}
}