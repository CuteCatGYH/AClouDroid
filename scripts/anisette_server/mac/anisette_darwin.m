#import <Foundation/Foundation.h>
#import <objc/message.h>
#import <objc/runtime.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>

#include "anisette_darwin.h"

typedef id (*MsgSend0)(id, SEL);
typedef id (*MsgSend1)(id, SEL, id);

static void *g_aos_handle = NULL;
static void *g_ak_handle = NULL;

static char *dup_nsstring(id obj) {
    if (obj == nil || ![obj isKindOfClass:[NSString class]]) {
        return NULL;
    }
    const char *utf8 = [(NSString *)obj UTF8String];
    if (utf8 == NULL || utf8[0] == '\0') {
        return NULL;
    }
    return strdup(utf8);
}

static id call0(id target, const char *selName) {
    if (target == nil) {
        return nil;
    }
    SEL sel = sel_registerName(selName);
    if (![target respondsToSelector:sel]) {
        return nil;
    }
    return ((MsgSend0)objc_msgSend)(target, sel);
}

static id call1(id target, const char *selName, id arg) {
    if (target == nil) {
        return nil;
    }
    SEL sel = sel_registerName(selName);
    if (![target respondsToSelector:sel]) {
        return nil;
    }
    return ((MsgSend1)objc_msgSend)(target, sel, arg);
}

int fetch_anisette(AnisetteResult *out, char **err_out) {
    if (out == NULL) {
        if (err_out != NULL) {
            *err_out = strdup("invalid output pointer");
        }
        return -1;
    }
    memset(out, 0, sizeof(*out));
    if (err_out != NULL) {
        *err_out = NULL;
    }

    @autoreleasepool {
        @try {
            if (g_aos_handle == NULL) {
                g_aos_handle = dlopen("/System/Library/PrivateFrameworks/AOSKit.framework/AOSKit", RTLD_NOW);
                if (g_aos_handle == NULL) {
                    if (err_out != NULL) {
                        *err_out = strdup("failed to load AOSKit.framework");
                    }
                    return -1;
                }
            }
            if (g_ak_handle == NULL) {
                g_ak_handle = dlopen("/System/Library/PrivateFrameworks/AuthKit.framework/AuthKit", RTLD_NOW);
                if (g_ak_handle == NULL) {
                    if (err_out != NULL) {
                        *err_out = strdup("failed to load AuthKit.framework");
                    }
                    return -1;
                }
            }

            Class aosClass = NSClassFromString(@"AOSUtilities");
            Class akDeviceClass = NSClassFromString(@"AKDevice");
            if (aosClass == Nil || akDeviceClass == Nil) {
                if (err_out != NULL) {
                    *err_out = strdup("AOSUtilities or AKDevice class unavailable");
                }
                return -1;
            }

            id otpObj = call1((id)aosClass, "retrieveOTPHeadersForDSID:", @"-2");
            if (otpObj != nil && [otpObj isKindOfClass:[NSDictionary class]]) {
                NSDictionary *otp = (NSDictionary *)otpObj;
                out->md = dup_nsstring(otp[@"X-Apple-MD"]);
                out->mdm = dup_nsstring(otp[@"X-Apple-MD-M"]);
            }

            id serialObj = call0((id)aosClass, "machineSerialNumber");
            out->serial = dup_nsstring(serialObj);

            id dev = call0((id)akDeviceClass, "currentDevice");
            if (dev != nil) {
                out->lu = dup_nsstring(call0(dev, "localUserUUID"));
                out->client_info = dup_nsstring(call0(dev, "serverFriendlyDescription"));
                out->device_id = dup_nsstring(call0(dev, "uniqueDeviceIdentifier"));

                id locObj = call0(dev, "locale");
                if (locObj != nil && [locObj isKindOfClass:[NSLocale class]]) {
                    out->locale = dup_nsstring([(NSLocale *)locObj localeIdentifier]);
                }
            }

            return 0;
        } @catch (NSException *ex) {
            free_anisette_result(out);
            if (err_out != NULL) {
                NSString *name = [ex name] ?: @"NSException";
                const char *utf8 = [name UTF8String];
                *err_out = strdup(utf8 != NULL ? utf8 : "Objective-C exception");
            }
            return -1;
        }
    }
}

void free_anisette_result(AnisetteResult *r) {
    if (r == NULL) {
        return;
    }
    free(r->md);
    free(r->mdm);
    free(r->lu);
    free(r->client_info);
    free(r->device_id);
    free(r->locale);
    free(r->serial);
    memset(r, 0, sizeof(*r));
}
