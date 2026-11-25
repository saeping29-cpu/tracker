import unittest
from analyze import analyze
class Tests(unittest.TestCase):
    def row(self,x,y,t): return dict(x=str(x),y=str(y),width='100',height='100',image_timestamp_ns=str(t*1000000),frame='1')
    def test_inside(self):
        r=list(analyze([self.row(48,40,10),self.row(52,60,30)],45,55,50)); self.assertEqual(r[0]['classification'],'IN_ZONE'); self.assertEqual(r[0]['crossing_ms'],20)
    def test_outside(self): self.assertEqual(list(analyze([self.row(70,40,10),self.row(70,60,30)],45,55,50))[0]['classification'],'OUTSIDE')
    def test_gap(self): self.assertEqual(list(analyze([self.row(50,40,10),self.row(50,60,100)],45,55,50)),[])
if __name__=='__main__': unittest.main()
