#include "extension/extension.h"

/*
 * ASHMEM is an Android-only kernel interface. The standalone Linux sanitizer
 * build still links the CLI option, so provide a no-op callback for it.
 */
int ashmem_memfd_callback(
	Extension *extension,
	ExtensionEvent event,
	intptr_t data1,
	intptr_t data2)
{
	(void)extension;
	(void)event;
	(void)data1;
	(void)data2;
	return 0;
}
