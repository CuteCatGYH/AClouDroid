#ifndef ANISETTE_DARWIN_H
#define ANISETTE_DARWIN_H

typedef struct {
    char *md;
    char *mdm;
    char *lu;
    char *client_info;
    char *device_id;
    char *locale;
    char *serial;
} AnisetteResult;

int fetch_anisette(AnisetteResult *out, char **err_out);
void free_anisette_result(AnisetteResult *r);

#endif
