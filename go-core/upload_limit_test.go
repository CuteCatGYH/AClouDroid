package icloudcore

import "testing"

// The region decision is inferred from the docws host, so it is worth pinning down: getting it
// backwards either blocks international users who have no limit, or lets 云上贵州 users start a
// transfer that is guaranteed to fail at 1 GiB.
func TestDocsHostRegionClassification(t *testing.T) {
	cases := []struct {
		name     string
		docsURL  string
		mainland bool
	}{
		{"guizhou", "https://p203-docws.icloud.com.cn", true},
		{"guizhou other partition", "https://p141-docws.icloud.com.cn", true},
		{"international", "https://p141-docws.icloud.com", false},
		{"international uswest", "https://p203-docws.icloud.com", false},
		{"empty", "", false},
		// Guard against a naive `strings.Contains(url, ".cn")`, which would also match
		// "icloud.com" hosts that merely happen to contain a ".cn"-like substring elsewhere.
		{"lookalike", "https://p203-docws.icloud.com/ws/cn", false},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := isMainlandChinaHost(tc.docsURL)
			if got != tc.mainland {
				t.Fatalf("isMainlandChinaHost(%q) = %v, want %v", tc.docsURL, got, tc.mainland)
			}
		})
	}
}

// A zero limit must mean "no restriction". Treating it as "zero bytes allowed" would block every
// upload for international accounts.
func TestZeroLimitMeansUnrestricted(t *testing.T) {
	info := UploadLimitInfo{}
	if info.SingleFileLimitBytes != 0 {
		t.Fatalf("zero value should have no limit, got %d", info.SingleFileLimitBytes)
	}
}
